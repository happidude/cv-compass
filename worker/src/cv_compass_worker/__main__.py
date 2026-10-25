"""Local CLI: exit0/1/2 imply delivered JSON; exit3 always means no delivery."""
import argparse
import json
from pathlib import Path
import re
import sys
from .config import Config
from .contracts import fit_result, validate_request
from .pipeline import empty_result, process, publish
from .sandbox import isolation_report


def receipt(code: str, delivered: bool, status: str | None = None) -> None:
    event = {'code': code, 'delivered': delivered}
    if status is not None:
        event['status'] = status
    print(json.dumps(event), file=sys.stderr)


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        receipt('INVALID_REQUEST', False)
        raise SystemExit(3)


def validated_identity(request: object) -> bool:
    if not isinstance(request, dict) or request.get('schema_version') != 'cv-compass.ocr/2.0':
        return False
    return all(isinstance(request.get(k), str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', request[k])
               for k in ('request_id', 'document_id'))


def main(argv=None, *, cancelled=None):
    parser = SafeParser(description='Offline PDF to page text; production remains gated')
    for name in ('config', 'workspace', 'request', 'output', 'verification-report'):
        parser.add_argument('--'+name, required=True, type=Path)
    parser.add_argument('--synthetic-only', action='store_true')
    parser.add_argument('--local-only', action='store_true')
    args = parser.parse_args(argv)
    request, config, diagnostics = None, Config(), []
    stage_code = 'INVALID_REQUEST'
    try:
        if args.request.stat().st_size > 16384:
            raise ValueError('INVALID_REQUEST')
        request = json.loads(args.request.read_text(encoding='utf-8'))
        validate_request(request)
        stage_code = 'INVALID_CONFIGURATION'
        config = Config.load(args.config)
        if config.mode != 'diagnostic' or args.synthetic_only == args.local_only:
            raise ValueError('INVALID_CONFIGURATION')
        stage_code = 'INTERNAL_ERROR'
        result = (process(request, args.workspace, config, diagnostics) if cancelled is None
                  else process(request, args.workspace, config, diagnostics, cancelled=cancelled))
    except Exception:
        if not validated_identity(request):
            receipt(stage_code, False)
            return 3
        # Invalid config cannot dictate result/output limits. Use canonical
        # envelope defaults only, never open a PDF or load an engine here.
        config = Config()
        result = empty_result(request, config)
        result['config_fingerprint'] = None
        result['errors'] = [{'code': stage_code, 'stage': 'request'}]
        result['timings_ms']['total'] = 0
    try:
        data = fit_result(result, config)
        status = json.loads(data)['status']
        report = isolation_report(config, diagnostics)
        report.update(engine_diagnostics=diagnostics, engines=result['engines'],
                      timings_ms=result['timings_ms'], config_fingerprint=result['config_fingerprint'])
        publish(args.verification_report, (json.dumps(report, indent=2)+'\n').encode())
        publish(args.output, data)
    except Exception as exc:
        receipt('OUTPUT_LIMIT' if isinstance(exc, ValueError) and str(exc) == 'OUTPUT_LIMIT' else 'OUTPUT_WRITE_FAILED', False)
        return 3
    receipt('RESULT_DELIVERED', True, status)
    return {'success': 0, 'partial': 1, 'failed': 2}[status]


if __name__ == '__main__':
    sys.exit(main())
