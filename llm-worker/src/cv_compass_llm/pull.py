"""Single-job pull loop with lease fencing and concurrent heartbeat supervision."""

import logging
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
from .lifecycle import call_metrics
from .pipeline import extract
from .progress import command_logging, context, endpoint, event, model_name
from .prompts import prompt_version
from .providers.base import ProviderError
from .pull_payload import (
    ENGINE,
    PERSONAL,
    PROTOCOL,
    WORKER_ISSUES,
    compact_result,
    input_document,
)


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

    def __init__(self, provider, supervisor, *, ctx_guard=False) -> None:
        self.provider = provider
        self.name = provider.name
        self.supervisor = supervisor
        self.failure = None
        self.last_metrics = {}
        self.ctx_guard = ctx_guard

    def generate(
        self,
        prompt: str,
        json_schema: dict,
        *,
        num_ctx: int | None = None,
        timeout_s: float | None = None,
    ) -> str:
        self.last_metrics = {}
        if self.failure:
            raise ProviderError(self.failure)
        if self.supervisor.lost or self.supervisor.outcome_started:
            raise ProviderError('PROVIDER_TIMEOUT')
        try:
            options = {
                key: value
                for key, value in (('num_ctx', num_ctx), ('timeout_s', timeout_s))
                if value is not None
            }
            response = self.provider.generate(prompt, json_schema, **options)
            self.last_metrics = getattr(self.provider, 'last_metrics', None) or {}
            return response
        except ProviderError as error:
            if self.ctx_guard and str(error) == 'OLLAMA_CONTEXT_OVERFLOW':
                raise
            self.failure = (
                'PROVIDER_TIMEOUT'
                if str(error) in ('OLLAMA_TIMEOUT', 'PROVIDER_TIMEOUT')
                else 'PROVIDER_ERROR'
            )
            raise


class _Attempt:
    def __init__(self, client, claim, token, counts) -> None:
        self.client, self.token, self.counts = client, token, counts
        self.path = '/attempts/' + str(UUID(claim['attemptId']))
        self.short_id = str(UUID(claim['attemptId']))[:8]
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
        self.failure_code = 'INTERNAL_ERROR'
        self.thread = threading.Thread(
            target=self._heartbeat, name='mapping-heartbeat', daemon=True
        )

    def _error(self, error: BackendError) -> None:
        self.counts[error.code] += 1
        event('backend_error', level=logging.ERROR, attempt=self.short_id, code=error.code)

    def _lost(self, code: str) -> None:
        self.lost = True
        event('heartbeat_lost', level=logging.WARNING, attempt=self.short_id, code=code)

    def outcome(self, kind: str, body: bytes) -> None:
        """Called under the lock: an uncertain outcome must never become a new one."""
        if self.lost or self.outcome_started:
            return
        if time.monotonic() >= min(self.lease, self.hard):
            self._lost('LEASE_EXPIRED')
            self.counts['LEASE_EXPIRED'] += 1
            return
        self.outcome_started = True
        event('submitting', attempt=self.short_id)
        try:
            status, reply = self.client.request(
                'PUT',
                self.path + '/' + kind,
                self.token,
                body,
                deadline=min(self.lease, self.hard),
            )
            expected = 'ACCEPTED' if kind == 'result' else 'FAILED'
            if status != 200 or reply.get('status') != expected:
                raise BackendError('BACKEND_BAD_RESPONSE')
            self.counts['accepted' if kind == 'result' else 'failed'] += 1
            if kind == 'result':
                event('accepted', attempt=self.short_id)
            else:
                event('failed', attempt=self.short_id, code=self.failure_code)
        except BackendError as error:
            self._error(error)

    def failure(self, code: str) -> None:
        self.failure_code = code
        self.outcome('failure', encode({'code': code}))

    def _heartbeat(self) -> None:
        try:
            while not self.stop.is_set():
                now = time.monotonic()
                delay = max(
                    0,
                    min(
                        self.interval,
                        self.hard - self.interval - now,
                        (self.lease - now) / 2,
                    ),
                )
                if self.stop.wait(delay):
                    return
                with self.lock:
                    if self.stop.is_set() or self.outcome_started or self.lost:
                        return
                    now = time.monotonic()
                    if now >= self.lease:
                        self._lost('LEASE_EXPIRED')
                        self.counts['LEASE_EXPIRED'] += 1
                        return
                    if now >= self.hard - self.interval:
                        self.failure('PROVIDER_TIMEOUT')
                        return
                    try:
                        status, reply = self.client.request(
                            'PUT',
                            self.path + '/lease',
                            self.token,
                            b'{}',
                            deadline=min(self.lease, self.hard - self.interval),
                        )
                        if status != 200:
                            raise BackendError('BACKEND_BAD_RESPONSE')
                        self.lease = min(
                            self.hard,
                            time.monotonic() + _instant(reply['leaseExpiresAt']) - time.time(),
                        )
                        event('heartbeat', level=logging.DEBUG, attempt=self.short_id)
                    except BackendError as error:
                        self._error(error)
                        if (
                            error.status not in (401, 409)
                            and time.monotonic() >= self.hard - self.interval
                            and time.monotonic() < self.lease
                        ):
                            self.failure('PROVIDER_TIMEOUT')
                            return
                        # Exhausted transport retries leave renewal uncertain. Abandon safely.
                        self._lost(error.code)
                        return
        except Exception:
            with self.lock:
                self._lost('BACKEND_BAD_RESPONSE')
                self.counts['BACKEND_BAD_RESPONSE'] += 1

    def close(self) -> None:
        self.stop.set()
        if self.thread.is_alive():
            self.thread.join()


def _definition(claim: dict) -> None:
    definition = claim['definition']
    fields = definition['fields']
    if (
        definition['schemaVersion'] != 4
        or definition['engineCompatibility'] != ENGINE
        or set(fields) != {*PERSONAL, 'experiences'}
        or any(type(row['enabled']) is not bool for row in fields.values())
        or type(definition['other']['enabled']) is not bool
        or type(definition['other']['maxItems']) is not int
        or not 0 <= definition['other']['maxItems'] <= 50
        or not re.fullmatch(r'[0-9a-f]{64}', claim['inputSha256'])
    ):
        raise ValueError('BACKEND_BAD_RESPONSE')


def _shutdown_unstarted(client, claim, token, counts) -> None:
    """No outcome can have started before the supervisor has been constructed."""
    try:
        path = '/attempts/' + str(UUID(claim['attemptId'])) + '/failure'
        expiry = min(_instant(claim['leaseExpiresAt']), _instant(claim['hardDeadlineAt']))
        event('submitting', attempt=str(UUID(claim['attemptId']))[:8])
        status, reply = client.request(
            'PUT',
            path,
            token,
            encode({'code': 'WORKER_SHUTDOWN'}),
            deadline=time.monotonic() + expiry - time.time(),
        )
        if status == 200 and reply.get('status') == 'FAILED':
            counts['failed'] += 1
            event(
                'failed',
                attempt=str(UUID(claim['attemptId']))[:8],
                code='WORKER_SHUTDOWN',
            )
    except BackendError as error:
        counts[error.code] += 1
        event('backend_error', level=logging.ERROR, code=error.code)
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
        except ValueError, TypeError, KeyError, OverflowError:
            counts['BACKEND_BAD_RESPONSE'] += 1
            event('backend_error', level=logging.ERROR, code='BACKEND_BAD_RESPONSE')
            return
        event(
            'claimed',
            attempt=attempt.short_id,
            engine=ENGINE if claim['engineVersion'] == ENGINE else 'UNSUPPORTED',
        )
        attempt.thread.start()
        if claim['engineVersion'] != ENGINE:
            failure = 'UNSUPPORTED_ENGINE'
        else:
            _definition(claim)
            try:
                status, data = client.request(
                    'GET',
                    attempt.path + '/input',
                    token,
                    deadline=min(attempt.lease, attempt.hard),
                )
                if status != 200:
                    raise ValueError('INVALID_INPUT')
                document = input_document(data)
                del data
                event(
                    'input_fetched',
                    attempt=attempt.short_id,
                    pages=len(document.pages),
                    chars=sum(len(page.raw_text) for page in document.pages),
                )
            except ValueError, TypeError, KeyError:
                failure = 'INVALID_INPUT'
            if failure is None:
                observed = _ObservedProvider(provider, attempt, ctx_guard=config.llm.ctx_guard)
                cascade = bool(config.llm.fallback_model)
                backup = (
                    _ObservedProvider(
                        provider.fallback_provider, attempt, ctx_guard=config.llm.ctx_guard
                    )
                    if cascade
                    else None
                )
                reasons = ()
                context_metrics = {}
                started = time.monotonic()

                def on_fallback(codes):
                    nonlocal reasons
                    reasons = codes

                def on_context(telemetry):
                    nonlocal context_metrics
                    context_metrics = {
                        key: telemetry[key] for key in ('num_ctx', 'ctx_retry') if key in telemetry
                    }
                    for discarded in telemetry['discarded']:
                        event('ctx_discarded', attempt=attempt.short_id, **discarded)

                def on_block(index, total, seconds):
                    current = backup if reasons else observed
                    metrics = call_metrics(current.last_metrics)
                    metrics.pop('total_s', None)
                    for key in ('prompt_s', 'output_s', 'load_s'):
                        if key in metrics:
                            metrics[key] = f'{metrics[key]:.3f}'
                    if 'tok_s' in metrics:
                        metrics['tok_s'] = f'{metrics["tok_s"]:.2f}'
                    event(
                        'extraction_block',
                        attempt=attempt.short_id,
                        block=f'{index}/{total}',
                        seconds=f'{seconds:.3f}',
                        **context_metrics,
                        **metrics,
                    )

                result = extract(
                    document,
                    observed,
                    max_blocks=config.llm.max_blocks,
                    on_block=on_block,
                    mode=config.llm.mode,
                    whole_timeout=config.llm.whole_timeout,
                    config=config.llm,
                    on_context=on_context,
                    **(
                        {'fallback_provider': backup, 'on_fallback': on_fallback} if cascade else {}
                    ),
                )
                issues = Counter(
                    (issue.code if issue.field is None else f'{issue.code}.{issue.field}')
                    for issue in result.issues
                    if issue.code
                    in WORKER_ISSUES
                    | {'LLM_INVALID_JSON', 'PROVIDER_ERROR', 'OUTPUT_TRUNCATED', 'CONTEXT_OVERFLOW'}
                )
                fallback = (
                    {'fallback': 'blocks'}
                    if any(issue.code == 'WHOLE_TOO_LONG' for issue in result.issues)
                    else {}
                )
                if cascade:
                    fallback.update(
                        cascade='yes' if reasons else 'no',
                        cascade_reason=','.join(reasons) or 'NONE',
                    )
                    if reasons:
                        counts['fallbacks'] += 1
                        counts.update('fallback_reason.' + code for code in reasons)
                event(
                    'extraction_done',
                    attempt=attempt.short_id,
                    mode=config.llm.mode,
                    seconds=f'{time.monotonic() - started:.3f}',
                    issues=','.join(f'{code}:{issues[code]}' for code in sorted(issues))
                    or 'NONE:0',
                    **fallback,
                )
                provider_failure = observed.failure or (backup.failure if backup else None)
                if provider_failure:
                    failure = provider_failure
                    event(
                        'provider_error',
                        level=logging.ERROR,
                        attempt=attempt.short_id,
                        code=failure,
                    )
                elif any(issue.code == 'LLM_INVALID_JSON' for issue in result.issues):
                    failure = 'INVALID_OUTPUT'
                else:
                    body = compact_result(result, claim, provenance)
                    kind = 'result'
    except BackendError as error:
        counts[error.code] += 1
        event('backend_error', level=logging.ERROR, code=error.code)
        if error.code in ('BACKEND_BAD_RESPONSE', 'BACKEND_RESPONSE_TOO_LARGE'):
            failure = 'INVALID_INPUT'
        else:
            # An uncertain input fetch or lost fencing must not produce an outcome.
            if attempt is not None:
                attempt.lost = True
    except ProviderError as error:
        failure = (
            'PROVIDER_TIMEOUT'
            if str(error) in ('OLLAMA_TIMEOUT', 'PROVIDER_TIMEOUT')
            else 'PROVIDER_ERROR'
        )
        event('provider_error', level=logging.ERROR, code=failure)
    except ValueError as error:
        failure = (
            str(error) if str(error) in ('RESULT_TOO_LARGE', 'INVALID_OUTPUT') else 'INTERNAL_ERROR'
        )
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
    """Scope stderr logging to this command and preserve the stdout counters."""
    with context(worker=config.worker_name), command_logging(config.log_level):
        return _run_pull(config, provider, once=once)


def _run_pull(config: PullConfig, provider, *, once: bool) -> int:
    counts = Counter(claimed=0, accepted=0, failed=0)
    provenance = {
        'model': config.llm.provenance_model,
        'promptVersion': prompt_version(config.llm.mode, config.llm),
        'workerVersion': config.worker_version,
    }
    client = BackendClient(config)
    claim_body = encode(
        {'protocol': PROTOCOL, 'engineVersions': [ENGINE], 'provenance': provenance}
    )
    exit_code = 0
    last_waiting = {}

    def waiting(code: str) -> None:
        now = time.monotonic()
        previous = last_waiting.get(code)
        if previous is None or now - previous >= 60:
            event('waiting', code=code)
            last_waiting[code] = now

    model = model_name(config.llm.model) if config.llm.model != config.worker_token else 'REDACTED'
    event(
        'worker_start',
        model=model,
        ollama=endpoint(config.llm.base_url),
        backend=endpoint(config.backend_url),
        poll_s=config.poll_s,
        log_level=config.log_level,
        mode=config.llm.mode,
        prompt_schema_text=int(config.llm.prompt_schema_text),
        whole_personal=int(config.llm.whole_personal),
        max_output_tokens=config.llm.max_output_tokens or 0,
        keep_alive_set=int(config.llm.keep_alive is not None),
        ctx_chars_per_token=config.llm.ctx_chars_per_token,
        ctx_fine_tiers=int(config.llm.ctx_fine_tiers),
        ctx_guard=int(config.llm.ctx_guard),
        num_ctx=config.llm.num_ctx or 0,
    )
    try:
        token, key = secrets.token_urlsafe(32), str(uuid4())
        while True:
            try:
                status, claim = client.request('POST', '/claims', token, claim_body, claim_key=key)
            except BackendError as error:
                counts[error.code] += 1
                if error.status in (0, 503):
                    waiting(error.code)
                else:
                    event('backend_error', level=logging.ERROR, code=error.code)
                if once or (error.status not in (0, 503)):
                    exit_code = 2
                    break
                time.sleep(config.poll_s)
                continue
            # Successful contact ends outage throttling; consecutive idle polls stay quiet.
            last_waiting = {
                code: stamp for code, stamp in last_waiting.items() if code == 'NO_WORK'
            }
            if status == 204:
                waiting('NO_WORK')
                if once:
                    break
                time.sleep(config.poll_s)
                token, key = secrets.token_urlsafe(32), str(uuid4())
                continue
            if status not in (200, 201) or claim.get('status') not in (
                'ACTIVE',
                'ACCEPTED',
                'FAILED',
                'EXPIRED',
            ):
                counts['BACKEND_BAD_RESPONSE'] += 1
                event('backend_error', level=logging.ERROR, code='BACKEND_BAD_RESPONSE')
                exit_code = 2
                break
            if claim['status'] == 'ACTIVE':
                last_waiting.clear()
                run_attempt(client, config, provider, claim, token, provenance, counts)
            del claim
            if once:
                break
            token, key = secrets.token_urlsafe(32), str(uuid4())
    except KeyboardInterrupt:
        exit_code = 130
    finally:
        event('stopped')
        print(' '.join(f'{name}={counts[name]}' for name in ('claimed', 'accepted', 'failed')))
        for code in sorted(set(counts) - {'claimed', 'accepted', 'failed'}):
            print(f'{code}={counts[code]}')
    return exit_code
