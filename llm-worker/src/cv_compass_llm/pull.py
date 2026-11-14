"""Single-job pull loop with lease fencing and concurrent heartbeat supervision."""

import math
import re
import secrets
import threading
import time
from collections import Counter
from datetime import datetime
from uuid import UUID, uuid4

from .backend_client import BackendClient, BackendError, encode
from .config import PullConfig
from .pipeline import extract
from .prompts import PROMPT_VERSION
from .providers.base import ProviderError
from .pull_payload import ENGINE, PERSONAL, PROTOCOL, compact_result, input_document


def _instant(value: str) -> float:
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.utcoffset() is None:
        raise ValueError('BACKEND_BAD_RESPONSE')
    return parsed.timestamp()


def _interval(value: str) -> float:
    match = re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?', value)
    if not match or not any(match.groups()):
        raise ValueError('BACKEND_BAD_RESPONSE')
    hours, minutes, seconds = (float(item or 0) for item in match.groups())
    duration = hours * 3600 + minutes * 60 + seconds
    if not math.isfinite(duration) or duration <= 0:
        raise ValueError('BACKEND_BAD_RESPONSE')
    return duration


class _ObservedProvider:
    """L1 isolates ProviderError, so remember it for L5's whole-attempt outcome."""

    def __init__(self, provider, supervisor) -> None:
        self.provider = provider
        self.name = provider.name
        self.supervisor = supervisor
        self.failure = None

    def generate(self, prompt: str, json_schema: dict) -> str:
        if self.failure:
            raise ProviderError(self.failure)
        if self.supervisor.lost or self.supervisor.outcome_started:
            raise ProviderError('PROVIDER_TIMEOUT')
        try:
            return self.provider.generate(prompt, json_schema)
        except ProviderError as error:
            self.failure = ('PROVIDER_TIMEOUT' if str(error) in
                            ('OLLAMA_TIMEOUT', 'PROVIDER_TIMEOUT') else 'PROVIDER_ERROR')
            raise


class _Attempt:
    def __init__(self, client, claim, token, counts) -> None:
        self.client, self.token, self.counts = client, token, counts
        self.path = '/attempts/' + str(UUID(claim['attemptId']))
        self.interval = _interval(claim['heartbeatInterval'])
        wall, steady = time.time(), time.monotonic()
        self.hard = steady + _instant(claim['hardDeadlineAt']) - wall
        self.lease = steady + _instant(claim['leaseExpiresAt']) - wall
        if self.lease > self.hard:
            raise ValueError('BACKEND_BAD_RESPONSE')
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.lost = False
        self.outcome_started = False
        self.thread = threading.Thread(target=self._heartbeat, name='mapping-heartbeat', daemon=True)

    def _error(self, error: BackendError) -> None:
        self.counts[error.code] += 1

    def outcome(self, kind: str, body: bytes) -> None:
        """Called under the lock: an uncertain outcome must never become a new one."""
        if self.lost or self.outcome_started:
            return
        if time.monotonic() >= min(self.lease, self.hard):
            self.lost = True
            self.counts['LEASE_EXPIRED'] += 1
            return
        self.outcome_started = True
        try:
            status, reply = self.client.request(
                'PUT', self.path + '/' + kind, self.token, body,
                deadline=min(self.lease, self.hard),
            )
            expected = 'ACCEPTED' if kind == 'result' else 'FAILED'
            if status != 200 or reply.get('status') != expected:
                raise BackendError('BACKEND_BAD_RESPONSE')
            self.counts['accepted' if kind == 'result' else 'failed'] += 1
        except BackendError as error:
            self._error(error)

    def failure(self, code: str) -> None:
        self.outcome('failure', encode({'code': code}))

    def _heartbeat(self) -> None:
        try:
            while not self.stop.is_set():
                now = time.monotonic()
                delay = max(0, min(self.interval, self.hard - self.interval - now,
                                   (self.lease - now) / 2))
                if self.stop.wait(delay):
                    return
                with self.lock:
                    if self.stop.is_set() or self.outcome_started or self.lost:
                        return
                    now = time.monotonic()
                    if now >= self.lease:
                        self.lost = True
                        self.counts['LEASE_EXPIRED'] += 1
                        return
                    if now >= self.hard - self.interval:
                        self.failure('PROVIDER_TIMEOUT')
                        return
                    try:
                        status, reply = self.client.request(
                            'PUT', self.path + '/lease', self.token, b'{}',
                            deadline=min(self.lease, self.hard - self.interval),
                        )
                        if status != 200:
                            raise BackendError('BACKEND_BAD_RESPONSE')
                        self.lease = min(self.hard, time.monotonic() +
                                         _instant(reply['leaseExpiresAt']) - time.time())
                    except BackendError as error:
                        self._error(error)
                        if (error.status not in (401, 409)
                                and time.monotonic() >= self.hard - self.interval
                                and time.monotonic() < self.lease):
                            self.failure('PROVIDER_TIMEOUT')
                            return
                        # Exhausted transport retries leave renewal uncertain. Abandon safely.
                        self.lost = True
                        return
        except Exception:
            with self.lock:
                self.lost = True
                self.counts['BACKEND_BAD_RESPONSE'] += 1

    def close(self) -> None:
        self.stop.set()
        if self.thread.is_alive():
            self.thread.join()


def _definition(claim: dict) -> None:
    definition = claim['definition']
    fields = definition['fields']
    if (definition['schemaVersion'] != 4 or definition['engineCompatibility'] != ENGINE
            or set(fields) != {*PERSONAL, 'experiences'}
            or any(type(row['enabled']) is not bool for row in fields.values())
            or type(definition['other']['enabled']) is not bool
            or type(definition['other']['maxItems']) is not int
            or not 0 <= definition['other']['maxItems'] <= 50
            or not re.fullmatch(r'[0-9a-f]{64}', claim['inputSha256'])):
        raise ValueError('BACKEND_BAD_RESPONSE')


def _shutdown_unstarted(client, claim, token, counts) -> None:
    """No outcome can have started before the supervisor has been constructed."""
    try:
        path = '/attempts/' + str(UUID(claim['attemptId'])) + '/failure'
        expiry = min(_instant(claim['leaseExpiresAt']), _instant(claim['hardDeadlineAt']))
        status, reply = client.request(
            'PUT', path, token, encode({'code': 'WORKER_SHUTDOWN'}),
            deadline=time.monotonic() + expiry - time.time(),
        )
        if status == 200 and reply.get('status') == 'FAILED':
            counts['failed'] += 1
    except BackendError as error:
        counts[error.code] += 1
    except Exception:
        counts['BACKEND_BAD_RESPONSE'] += 1


def run_attempt(client, config, provider, claim, token, provenance, counts) -> None:
    """Keep OCR/result locals confined to this call, including failure and shutdown."""
    kind, body, failure = 'failure', None, None
    interrupted = False
    attempt = None
    try:
        counts['claimed'] += 1
        try:
            attempt = _Attempt(client, claim, token, counts)
        except (ValueError, TypeError, KeyError, OverflowError):
            counts['BACKEND_BAD_RESPONSE'] += 1
            return
        attempt.thread.start()
        if claim['engineVersion'] != ENGINE:
            failure = 'UNSUPPORTED_ENGINE'
        else:
            _definition(claim)
            try:
                status, data = client.request('GET', attempt.path + '/input', token,
                                              deadline=min(attempt.lease, attempt.hard))
                if status != 200:
                    raise ValueError('INVALID_INPUT')
                document = input_document(data)
                del data
            except (ValueError, TypeError, KeyError):
                failure = 'INVALID_INPUT'
            if failure is None:
                observed = _ObservedProvider(provider, attempt)
                result = extract(document, observed, max_blocks=config.llm.max_blocks)
                if observed.failure:
                    failure = observed.failure
                elif any(issue.code == 'LLM_INVALID_JSON' for issue in result.issues):
                    failure = 'INVALID_OUTPUT'
                else:
                    body = compact_result(result, claim, provenance)
                    kind = 'result'
    except BackendError as error:
        counts[error.code] += 1
        if error.code in ('BACKEND_BAD_RESPONSE', 'BACKEND_RESPONSE_TOO_LARGE'):
            failure = 'INVALID_INPUT'
        else:
            # An uncertain input fetch or lost fencing must not produce an outcome.
            if attempt is not None:
                attempt.lost = True
    except ProviderError as error:
        failure = ('PROVIDER_TIMEOUT' if str(error) in ('OLLAMA_TIMEOUT', 'PROVIDER_TIMEOUT')
                   else 'PROVIDER_ERROR')
    except ValueError as error:
        failure = str(error) if str(error) in ('RESULT_TOO_LARGE', 'INVALID_OUTPUT') else 'INTERNAL_ERROR'
    except KeyboardInterrupt:
        interrupted = True
        failure = 'WORKER_SHUTDOWN'
    except Exception:
        failure = 'INTERNAL_ERROR'
    finally:
        if attempt is not None:
            try:
                attempt.close()
            except KeyboardInterrupt:
                interrupted = True
                failure = 'WORKER_SHUTDOWN'
                attempt.close()
    if attempt is None:
        if interrupted:
            _shutdown_unstarted(client, claim, token, counts)
            raise KeyboardInterrupt
        return
    try:
        with attempt.lock:
            if not interrupted and time.monotonic() >= attempt.hard - attempt.interval:
                failure = 'PROVIDER_TIMEOUT'
            if failure:
                attempt.failure(failure)
            elif body is not None:
                attempt.outcome(kind, body)
    except KeyboardInterrupt:
        interrupted = True
        # If transmission already started its outcome is uncertain; do not change the body.
        with attempt.lock:
            attempt.failure('WORKER_SHUTDOWN')
    if interrupted:
        raise KeyboardInterrupt


def run_pull(config: PullConfig, provider, *, once: bool = False) -> int:
    """Claim at most one job at a time; output only fixed counters and error codes."""
    counts = Counter(claimed=0, accepted=0, failed=0)
    provenance = {'model': config.llm.model, 'promptVersion': PROMPT_VERSION,
                  'workerVersion': config.worker_version}
    client = BackendClient(config)
    claim_body = encode({'protocol': PROTOCOL, 'engineVersions': [ENGINE],
                         'provenance': provenance})
    exit_code = 0
    try:
        token, key = secrets.token_urlsafe(32), str(uuid4())
        while True:
            try:
                status, claim = client.request('POST', '/claims', token, claim_body, claim_key=key)
            except BackendError as error:
                counts[error.code] += 1
                if once or (error.status not in (0, 503)):
                    exit_code = 2
                    break
                time.sleep(config.poll_s)
                continue
            if status == 204:
                if once:
                    break
                time.sleep(config.poll_s)
                token, key = secrets.token_urlsafe(32), str(uuid4())
                continue
            if status not in (200, 201) or claim.get('status') not in (
                'ACTIVE', 'ACCEPTED', 'FAILED', 'EXPIRED',
            ):
                counts['BACKEND_BAD_RESPONSE'] += 1
                exit_code = 2
                break
            if claim['status'] == 'ACTIVE':
                run_attempt(client, config, provider, claim, token, provenance, counts)
            del claim
            if once:
                break
            token, key = secrets.token_urlsafe(32), str(uuid4())
    except KeyboardInterrupt:
        exit_code = 130
    finally:
        print(' '.join(f'{name}={counts[name]}' for name in ('claimed', 'accepted', 'failed')))
        for code in sorted(set(counts) - {'claimed', 'accepted', 'failed'}):
            print(f'{code}={counts[code]}')
    return exit_code
