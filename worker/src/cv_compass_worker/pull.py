"""Single-flight synthetic pull supervisor; reuses M6 CLI and owned child cleanup.

An uncertain outcome/cleanup retains a private durable spool and halts intake.
Accepted result replay may retrieve its receipt after expiry, never reruns OCR.
"""
import argparse
from datetime import datetime
import os
from pathlib import Path
import re
import secrets
import shutil
import sys
import threading
import time
import tomllib
import uuid
import logging
from contextvars import copy_context
from .observability import configure, context, event, Event, report_failure
from .diagnostics import WorkerFailure, Reason, annotate, diagnostic, failure_scope
from .config import Config
from .pull_config import PullConfig
from .pull_http import BASE, PROTOCOL, WorkerHttp, HttpFailure, InputIntegrityError, DownloadUnavailable, json_bytes, strict_json


def claim_context(claim):
    """Only syntactically established internal IDs, including on a rejected claim."""
    if not isinstance(claim, dict):
        return {}
    result = {}
    request = claim.get('ocr_request')
    for key, value in (('jobId', claim.get('job_id')), ('documentId', request.get('document_id') if isinstance(request, dict) else None)):
        if isinstance(value, str) and re.fullmatch(r'[1-9][0-9]{0,18}', value):
            result[key] = value
    attempt = claim.get('attempt_id')
    if isinstance(attempt, str) and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', attempt):
        result['attemptId'] = attempt
    return result


class Lease:
    def __init__(self, config, http, claim, token):
        self.config, self.http, self.claim, self.token = config, http, claim, token
        self.cancelled, self.stopped = threading.Event(), threading.Event()
        self.failure = None
        self._lock = threading.Lock()
        self._hard = float('inf')
        self._update(claim)
        inherited = copy_context()
        self.thread = threading.Thread(target=lambda: inherited.run(self._renew), name='ocr-heartbeat', daemon=True)

    def _update(self, claim):
        with failure_scope(Reason.LEASE_CLOCK):
            return self._update_clock(claim)

    def _update_clock(self, claim):
        def instant(name):
            text = claim[name]
            if not isinstance(text, str) or not text.endswith('Z'):
                raise WorkerFailure(Reason.LEASE_CLOCK)
            return datetime.fromisoformat(text.replace('Z', '+00:00'))
        server, expiry, hard = (instant(name) for name in ('server_time', 'lease_expires_at', 'hard_deadline_at'))
        remaining, hard_remaining = (expiry-server).total_seconds(), (hard-server).total_seconds()
        reserve=2*self.config.http_timeout_seconds # Connect/TLS plus absolute exchange budget.
        if not 0 < remaining <= hard_remaining or remaining <= self.config.heartbeat_seconds+reserve:
            raise WorkerFailure(Reason.LEASE_TIMING)
        now = time.monotonic()
        with self._lock:
            # A backwards DB clock must not extend the immutable local hard deadline.
            self._hard = min(self._hard, now+hard_remaining)
            self._expires = min(now+remaining-reserve, self._hard)

    def live(self):
        with self._lock:
            expired = time.monotonic() >= self._expires
        if expired:
            self.cancelled.set()
        return not self.cancelled.is_set()

    def _renew(self):
        while not self.stopped.wait(self.config.heartbeat_seconds):
            try:
                response = self.http.request('PUT', BASE+'/attempts/'+self.claim['attempt_id']+'/lease', b'{}',
                                             self.token, live=self.live)
                if response['attempt_id'] != self.claim['attempt_id']:
                    raise WorkerFailure(Reason.LEASE_IDENTITY)
                self._update(response)
                event(Event.HEARTBEAT, level=logging.DEBUG)
            except Exception as failure:
                self.failure = failure
                report_failure(failure, Reason.LEASE_LOST, name=Event.HEARTBEAT_FAILED, level=logging.WARN)
                self.cancelled.set()
                return

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        if exc[1] is not None and self.failure is not None and diagnostic(exc[1]).reason in (
                Reason.LEASE_LOST, Reason.LEASE_OR_CLEANUP_FAILED):
            exc[1]._ocr_diagnostic = diagnostic(self.failure)
        self.stopped.set()
        self.thread.join(2*self.config.http_timeout_seconds*self.config.max_http_attempts
                         + self.config.retry_backoff_seconds*self.config.max_http_attempts+1)
        if self.thread.is_alive():
            self.cancelled.set()
            if exc[1] is not None:
                exc[1].add_note('HEARTBEAT_NOT_REAPED; intake must remain halted')
                return False
            raise WorkerFailure(Reason.HEARTBEAT_NOT_REAPED)


def private_root(path):
    with failure_scope(Reason.PRIVATE_LINUX_SPOOL_REQUIRED):
        from .pipeline import real_path
        root = real_path(path)
        if os.name != 'posix' or not root.is_dir() or root.stat().st_uid != os.getuid() or root.stat().st_mode & 0o077:
            raise WorkerFailure(Reason.PRIVATE_LINUX_SPOOL_REQUIRED)
        return root


def write_once(path, data):
    with failure_scope(Reason.SPOOL_IO_FAILED):
        from .pipeline import publish
        publish(path, data)
        if os.name=='posix':
            descriptor=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)


def read_bounded(path, cap):
    with failure_scope(Reason.SPOOL_IO_FAILED):
        from .pipeline import real_path
        checked = real_path(path)
        if not checked.is_file() or checked.stat().st_size > cap:
            raise WorkerFailure(Reason.SPOOL_LIMIT)
        with checked.open('rb') as source:
            raw = source.read(cap+1)
        if len(raw) > cap:
            raise WorkerFailure(Reason.SPOOL_LIMIT)
        return raw


def submission(raw, exit_code, config, pipeline):
    with failure_scope(Reason.DELIVERY_CONTRACT):
        from .contracts import validate_result
        if len(raw) > config.max_result_bytes:
            raise WorkerFailure(Reason.RESULT_LIMIT)
        result = strict_json(raw)
        validate_result(result, pipeline)
        if result['status'] not in ('success', 'partial', 'failed') \
                or exit_code != {'success': 0, 'partial': 1, 'failed': 2}[result['status']]:
            raise WorkerFailure(Reason.DELIVERY_CONTRACT)
        prefix = json_bytes(dict(protocol=PROTOCOL, profile_revision=config.profile_revision, exit_code=exit_code,
                                delivered=True, provenance=dict(worker_commit=config.worker_commit, image_id=config.image_id)))
        # Preserve original M6 bytes and the complete envelope bytes for every resend.
        body = prefix[:-1]+b',"ocr_result":'+raw+b'}'
        if len(body) > config.max_envelope_bytes:
            raise WorkerFailure(Reason.ENVELOPE_LIMIT)
        return body, result


class PullSupervisor:
    def __init__(self, config, http, pipeline):
        self.config, self.http, self.pipeline = config, http, pipeline
        self.root = private_root(Path(config.spool_root))
        if pipeline.mode != 'diagnostic' or pipeline.adapter != 'docker' \
                or pipeline.docker_image != config.image_id or pipeline.fingerprint() != config.config_fingerprint:
            raise WorkerFailure(Reason.PIPELINE_REGISTRY)
        self.pipeline_bytes=read_bounded(Path(config.pipeline_config),16384)
        pinned=Config.parse(tomllib.loads(self.pipeline_bytes.decode('utf-8')))
        if pinned.fingerprint() != pipeline.fingerprint():
            raise WorkerFailure(Reason.PIPELINE_REGISTRY)

    def _quota(self):
        from .pipeline import real_path
        total = 0
        for path in self.root.rglob('*'):
            real_path(path)
            if path.is_file():
                total += path.stat().st_size
            elif not path.is_dir():
                raise WorkerFailure(Reason.SPOOL_TYPE)
        if total > self.config.max_spool_bytes:
            raise WorkerFailure(Reason.SPOOL_LIMIT)

    def once(self):
        # One turn owns context, including recovery/error paths. Do not leak IDs into the next poll.
        with context(workerId=os.environ.get('CV_LOG_WORKER_ID', 'unconfigured')):
            try:
                return self._once()
            except Exception as failure:
                report_failure(failure, Reason.SPOOL_IO_FAILED if isinstance(failure, OSError) else Reason.UNEXPECTED_WORKER_FAILURE)
                raise

    def _once(self):
        with failure_scope(Reason.SPOOL_IDENTITY):
            self._quota()
        if (self.root/'halt.json').exists():
            raise WorkerFailure(Reason.OPERATOR_RECOVERY_REQUIRED)
        pending = self.root/'claim.json'
        if not pending.exists():
            write_once(pending, json_bytes(dict(claim_id=str(uuid.uuid4()), token=secrets.token_urlsafe(32))))
        with failure_scope(Reason.SPOOL_IDENTITY):
            identity = strict_json(read_bounded(pending, 16384))
        token, claim_id = identity['token'], identity['claim_id']
        if str(uuid.UUID(claim_id)) != claim_id or not re.fullmatch(r'[A-Za-z0-9_-]{43}', token):
            raise WorkerFailure(Reason.SPOOL_IDENTITY)
        directory = self.root/claim_id
        # Crash after HTTP commit but before receipt: exact replay only, no heartbeat/download/OCR.
        if (directory/'submission.json').exists():
            with failure_scope(Reason.SPOOL_IDENTITY):
                saved = strict_json(read_bounded(directory/'claim-response.json', 16384))
            raw = read_bounded(directory/'submission.json', self.config.max_envelope_bytes)
            return self._recover(saved, directory, pending, token, 'result', raw)
        if (directory/'failure.json').exists():
            with failure_scope(Reason.SPOOL_IDENTITY):
                saved = strict_json(read_bounded(directory/'claim-response.json', 16384))
            raw = read_bounded(directory/'failure.json', 16384)
            return self._recover(saved, directory, pending, token, 'failure', raw)
        body = json_bytes(dict(protocol=PROTOCOL, profile_revision=self.config.profile_revision))
        claim = self.http.request('POST', BASE+'/claims', body, token, claim_id)
        if claim is None:
            event(Event.POLL_EMPTY, level=logging.DEBUG)
            pending.unlink()
            return False
        with context(**claim_context(claim)):
            try:
                self._claim(claim)
                event(Event.JOB_RECEIVED)
                return self._execute(claim, directory, pending, token)
            except Exception as failure:
                report_failure(failure)
                raise

    def _recover(self, saved, directory, pending, token, outcome, raw):
        with context(**claim_context(saved)):
            event(Event.RECOVERY_STARTED)
            try:
                event(Event.RESULT_SUBMITTING)
                receipt = self.http.request('PUT', BASE+'/attempts/'+saved['attempt_id']+'/'+outcome, raw, token)
                self._receipt(receipt, saved)
                self._clean(directory, pending)
                return True
            except Exception as failure:
                report_failure(failure)
                raise

    def _execute(self, claim, directory, pending, token):
        if directory.exists():
            raise WorkerFailure(Reason.INCOMPLETE_ATTEMPT_REQUIRES_OPERATOR)
        directory.mkdir(mode=0o700)
        write_once(directory/'claim-response.json', json_bytes(claim))
        with Lease(self.config, self.http, claim, token) as lease:
            request = claim['ocr_request']
            if lease._hard-time.monotonic() <= self.pipeline.document_timeout+2*self.config.http_timeout_seconds:
                self._failure(directory,claim,token,'CONFIGURATION_ERROR',lease)
                self._clean(directory,pending)
                return True
            try:
                self.http.download(claim['attempt_id'], token, directory/request['input_ref'],
                                   claim['size_bytes'], request['expected_sha256'], live=lease.live)
            except (DownloadUnavailable, HttpFailure, InputIntegrityError) as error:
                if getattr(error,'cleanup_failed',False):
                    raise
                if isinstance(error,HttpFailure) and error.status<500:
                    raise
                if lease.live():
                    code=('INPUT_HASH_MISMATCH' if isinstance(error,InputIntegrityError) else 'DOWNLOAD_UNAVAILABLE')
                    self._failure(directory,claim,token,code,lease)
                    self._clean(directory, pending)
                    return True
                raise
            # Transfer took time: don't start a full pipeline that can outlive the quarantine bound.
            if lease._hard-time.monotonic() <= self.pipeline.document_timeout+2*self.config.http_timeout_seconds:
                self._failure(directory,claim,token,'CONFIGURATION_ERROR',lease)
                self._clean(directory,pending)
                return True
            write_once(directory/'request.json', json_bytes(request))
            write_once(directory/'pipeline.toml',self.pipeline_bytes)
            from .__main__ import main as pipeline_main
            # In-process supervisor with heartbeat thread. Credentials never enter PDF child env/mounts.
            exit_code = pipeline_main(['--config', str(directory/'pipeline.toml'), '--workspace', str(directory),
                    '--request', str(directory/'request.json'), '--output', str(directory/'result.json'),
                    '--verification-report', str(directory/'verification.json'),
                    '--local-only' if self.config.local_only else '--synthetic-only'],
                    cancelled=lease.cancelled)
            if not (directory/'result.json').exists() or exit_code not in (0, 1, 2):
                failure = json_bytes(dict(protocol=PROTOCOL, code='OUTPUT_NOT_DELIVERED'))
                write_once(directory/'failure.json', failure)
                if not lease.live() or (directory/'.ocr-lock').exists():
                    raise WorkerFailure(Reason.LEASE_OR_CLEANUP_FAILED)
                receipt = self.http.request('PUT', BASE+'/attempts/'+claim['attempt_id']+'/failure',
                                            failure, token, live=lease.live)
            else:
                raw = read_bounded(directory/'result.json', self.config.max_result_bytes)
                envelope, result = submission(raw, exit_code, self.config, self.pipeline)
                write_once(directory/'submission.json', envelope)
                if not lease.live():
                    raise WorkerFailure(Reason.LEASE_LOST)
                event(Event.RESULT_SUBMITTING)
                receipt = self.http.request('PUT', BASE+'/attempts/'+claim['attempt_id']+'/result',
                                            envelope, token, live=lease.live)
            self._receipt(receipt, claim)
            if (directory/'.ocr-lock').exists() or (directory/'result.json').exists() \
                    and 'CLEANUP_FAILED' in strict_json(read_bounded(directory/'result.json', self.config.max_result_bytes))['warnings']:
                # Delivered result remains terminal; resource cleanup warning halts intake, not page status.
                raise WorkerFailure(Reason.CLEANUP_FAILED)
        self._clean(directory, pending)
        return True

    def _claim(self, claim):
        with failure_scope(Reason.CLAIM_CONTRACT):
            return self._validate_claim(claim)

    def _validate_claim(self, claim):
        from .contracts import validate_request
        mode_matches = (claim.get('synthetic_only') is False and claim.get('local_only') is True
                        if self.config.local_only else claim.get('synthetic_only') is True
                        and claim.get('local_only', False) is False)
        if claim.get('protocol') != PROTOCOL or claim.get('profile_revision') != self.config.profile_revision \
                or not mode_matches:
            raise WorkerFailure(Reason.CLAIM_CONTRACT)
        attempt = str(uuid.UUID(claim['attempt_id']))
        if attempt != claim['attempt_id'] or claim['input_path'] != BASE+'/attempts/'+attempt+'/input':
            raise WorkerFailure(Reason.CLAIM_IDENTITY)
        validate_request(claim['ocr_request'])
        if claim['ocr_request']['request_id'] != 'a_'+attempt.replace('-', '') \
                or claim['ocr_request']['document_id'] == '' \
                or claim['ocr_request']['input_ref'] != attempt.replace('-', '')+'.pdf':
            raise WorkerFailure(Reason.CLAIM_IDENTITY)
        if type(claim['size_bytes']) is not int or not 1 <= claim['size_bytes'] <= self.config.max_file_bytes:
            raise ValueError('INPUT_SIZE')

    @staticmethod
    def _receipt(receipt, claim):
        if not isinstance(receipt, dict) or receipt.get('accepted') is not True or receipt.get('attempt_id') != claim['attempt_id'] \
                or receipt.get('job_id') != claim['job_id']:
            raise WorkerFailure(Reason.RECEIPT_IDENTITY)
        with context(documentId=claim.get('ocr_request', {}).get('document_id', 'unknown'),
                     jobId=claim['job_id'], attemptId=claim['attempt_id']):
            event(Event.RESULT_ACKNOWLEDGED)

    def _failure(self,directory,claim,token,code,lease):
        failure=json_bytes(dict(protocol=PROTOCOL,code=code))
        write_once(directory/'failure.json',failure)
        receipt=self.http.request('PUT',BASE+'/attempts/'+claim['attempt_id']+'/failure',failure,token,live=lease.live)
        self._receipt(receipt,claim)

    def _clean(self, directory, pending):
        with failure_scope(Reason.CLEANUP_FAILED):
            return self._clean_owned(directory, pending)

    def _clean_owned(self, directory, pending):
        from .pipeline import real_path
        checked = real_path(directory)
        if checked.parent != self.root or checked.name != strict_json(read_bounded(pending, 16384))['claim_id'] \
                or (checked/'.ocr-lock').exists():
            raise WorkerFailure(Reason.CLEANUP_OWNERSHIP)
        if (checked/'result.json').exists() and 'CLEANUP_FAILED' in strict_json(
                read_bounded(checked/'result.json',self.config.max_result_bytes))['warnings']:
            raise WorkerFailure(Reason.CLEANUP_FAILED)
        shutil.rmtree(checked)
        pending.unlink()


def main(argv=None):
    parser = argparse.ArgumentParser(description='Synthetic M7 pull supervisor; production BLOCKED')
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args(argv)
    with context(workerId=os.environ.get('CV_LOG_WORKER_ID', 'unconfigured')):
        return _main(args)


def _main(args):
    initialized = False
    stage = Reason.LOG_CONFIGURATION_ERROR
    try:
        configure()
        initialized = True
        stage = Reason.PULL_CONFIGURATION
        config = PullConfig.load(args.config)
        stage = Reason.PIPELINE_CONFIG_INVALID
        pipeline = Config.load(Path(config.pipeline_config))
        http = WorkerHttp(config, os.environ.get(config.credential_env))
        stage = Reason.SPOOL_IO_FAILED
        supervisor = PullSupervisor(config, http, pipeline)
        import fcntl  # Ubuntu host supervisor only, never PDF child.
    except Exception as failure:
        report_failure(failure, stage, initialized=initialized)
        print('OCR pull configuration unavailable; production BLOCKED',file=sys.stderr)
        return 3
    descriptor=None
    owned=False
    try:
        descriptor = os.open(supervisor.root/'.pull.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        owned=True
        while True:
            supervisor.once()
            if args.once:
                return 0
            time.sleep(config.poll_seconds)
    except Exception as failure:
        report_failure(failure, Reason.WORKER_LOCK_FAILED)
        if owned and not (supervisor.root/'halt.json').exists():
            try:
                write_once(supervisor.root/'halt.json', json_bytes(dict(code='OPERATOR_RECOVERY_REQUIRED')))
            except OSError:
                print('OCR halt marker unavailable; inspect retained claim/outcome/cleanup state',file=sys.stderr)
        print('OCR pull halted; private spool retained; production BLOCKED', file=sys.stderr)
        return 3
    finally:
        if descriptor is not None:
            os.close(descriptor)


if __name__ == '__main__':
    sys.exit(main())
