"""Private batch extraction and aggregate-only command line reporting."""

import argparse
import json
import os
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from queue import Empty, Queue
from time import perf_counter
from typing import Literal

from .batch_io import input_directory, load_json, prepare_output, write_json
from .bench import BENCH_ERRORS, print_bench, run_bench
from .check import run_check
from .compare import compare
from .config import ENV_KEYS, LlmConfig, PullConfigError, load_config, load_pull_config
from .gold import draft_gold
from .lifecycle import call_metrics, lifecycle_report, metric_summary, print_report
from .ocr_input import load_document
from .pipeline import FALLBACK_REASONS, extract, fallback_reasons
from .prompts import prompt_version
from .providers.base import LlmProvider
from .providers.ollama import OllamaProvider
from .pull import run_pull

INPUT_ERRORS = {
    'INVALID_OCR',
    'INVALID_JSON',
    'INVALID_UTF8',
    'OCR_TOO_LARGE',
    'OCR_READ_ERROR',
    'INVALID_PAGE',
    'INVALID_PAGE_NUMBER',
    'INVALID_PAGE_STATUS',
    'INVALID_PAGE_TEXT',
}
CLI_ERRORS = {
    'UNSAFE_OUTPUT',
    'OUTPUT_NOT_EMPTY',
    'INVALID_INPUT_DIRECTORY',
    'INVALID_MAX_BLOCKS',
    'INVALID_LIMIT',
    'OLLAMA_HOST_NOT_LOCAL',
    'INVALID_MODEL',
    'INVALID_TIMEOUT',
    'MISSING_CONFIG',
    'INVALID_PROVIDER',
    'ENV_FILE_NOT_FOUND',
    'INVALID_ENV_FILE',
    'INVALID_TEMPERATURE',
    'INVALID_THINK',
    'INVALID_NUM_CTX',
    'INVALID_MODE',
    'INVALID_NAME_STYLE',
    'INVALID_WHOLE_TIMEOUT',
    'INVALID_PROMPT_SCHEMA_TEXT',
    'INVALID_WHOLE_PERSONAL',
    'INVALID_MAX_OUTPUT_TOKENS',
    'INVALID_KEEP_ALIVE',
    'INVALID_CTX_CHARS_PER_TOKEN',
    'INVALID_CTX_FINE_TIERS',
    'INVALID_CTX_GUARD',
    'INVALID_FALLBACK_MODEL',
    'INVALID_FALLBACK_DROP_RATIO',
    'INVALID_FALLBACK_CANDIDATES',
} | BENCH_ERRORS


class _MetricsProvider:
    """Observe every call, including whole-mode fallback, without changing orchestration."""

    def __init__(self, provider):
        self.provider = provider
        self.name = provider.name
        self.calls = []

    @property
    def last_metrics(self):
        return getattr(self.provider, 'last_metrics', None)

    def generate(self, prompt, json_schema, **options):
        try:
            response = self.provider.generate(prompt, json_schema, **options)
        except Exception:
            self.calls.append({})
            raise
        self.calls.append(call_metrics(getattr(self.provider, 'last_metrics', None)))
        return response


def run_batch(
    input_dir: str | Path,
    output_dir: str | Path,
    provider: LlmProvider,
    *,
    max_blocks: int = 20,
    limit: int | None = None,
    mode: str = 'blocks',
    whole_timeout: float = 900,
    config: LlmConfig | None = None,
    repo_root: Path | None | Literal['auto'] = 'auto',
    _providers: list[LlmProvider] | None = None,
    only_fallback_candidates: str | Path | None = None,
) -> dict:
    """Extract sorted OCR files without modifying any input artifact."""
    if type(max_blocks) is not int or max_blocks < 0:
        raise ValueError('INVALID_MAX_BLOCKS')
    if mode not in ('blocks', 'whole'):
        raise ValueError('INVALID_MODE')
    if limit is not None and (type(limit) is not int or limit < 0):
        raise ValueError('INVALID_LIMIT')
    source = input_directory(input_dir)
    paths = sorted(path for path in source.glob('*.json') if path.is_file())
    if only_fallback_candidates is not None:
        previous = input_directory(only_fallback_candidates)
        output = Path(output_dir).resolve()
        if output == previous or previous in output.parents or output in previous.parents:
            raise ValueError('UNSAFE_OUTPUT')
        selected = set()
        for path in sorted(previous.glob('*.result.json')):
            if path.is_symlink() or not path.is_file():
                continue
            payload = load_json(path, 'INVALID_FALLBACK_CANDIDATES')
            candidates = payload.get('fallbackCandidate', []) if isinstance(payload, dict) else None
            if not isinstance(candidates, list) or any(
                not isinstance(code, str) or code not in FALLBACK_REASONS for code in candidates
            ):
                raise ValueError('INVALID_FALLBACK_CANDIDATES')
            if candidates:
                selected.add(path.name.removesuffix('.result.json'))
        paths = [path for path in paths if not path.is_symlink() and path.stem in selected]
    if limit is not None:
        paths = paths[:limit]
    target = prepare_output(source, output_dir, repo_root=repo_root)
    errors, issues = Counter(), Counter()
    issues_by_field = Counter()
    ok, total_seconds = 0, 0.0
    calls = []
    context_counts = Counter()
    num_ctx_counts = Counter()
    # Bench reuses its configured provider for warmup and every measured batch.
    config = config if config is not None else getattr(provider, 'extraction_config', None)
    if not isinstance(config, LlmConfig):
        config = None
    cascade = config is not None and bool(config.fallback_model)
    fallback_counts = Counter()

    def process(path, local_provider):
        observed = _MetricsProvider(local_provider)
        backup = _MetricsProvider(local_provider.fallback_provider) if cascade else None
        reasons = ()
        contexts = []

        def on_fallback(codes):
            nonlocal reasons
            reasons = codes

        error_code, result = None, None
        started = perf_counter()
        try:
            document = load_document(path)
        except ValueError as error:
            code = str(error) if str(error) in INPUT_ERRORS else 'INVALID_OCR'
            error_code = code
            write_json(target / (path.stem + '.error.json'), {'error': code})
        else:
            result = extract(
                document,
                observed,
                max_blocks=max_blocks,
                mode=mode,
                whole_timeout=whole_timeout,
                config=config,
                on_context=contexts.append,
                **({'fallback_provider': backup, 'on_fallback': on_fallback} if cascade else {}),
            )
            if not cascade:
                reasons = fallback_reasons(
                    result,
                    document,
                    drop_ratio=config.fallback_drop_ratio if config is not None else 0.3,
                )
            payload = result.to_dict()
            if cascade or reasons:
                payload['fallbackCandidate'] = list(reasons)
            payload['seconds'] = perf_counter() - started
            write_json(target / (path.stem + '.result.json'), payload)
        return (
            error_code,
            result,
            perf_counter() - started,
            observed.calls + (backup.calls if backup is not None else []),
            reasons,
            contexts,
        )

    if _providers is not None and len(_providers) > 1:
        pending = Queue()
        for index, path in enumerate(paths):
            pending.put((index, path))

        def consume(local_provider):
            completed = []
            while True:
                try:
                    index, path = pending.get_nowait()
                except Empty:
                    return completed
                completed.append((index, process(path, local_provider)))

        with ThreadPoolExecutor(max_workers=len(_providers)) as pool:
            batches = pool.map(consume, _providers)
            results = [result for _, result in sorted(item for batch in batches for item in batch)]
    else:
        results = [process(path, provider) for path in paths]
    for error_code, result, elapsed, observations, reasons, contexts in results:
        calls.extend(observations)
        context_counts['ctx_retry'] += sum(row.get('ctx_retry', 0) for row in contexts)
        sizes = [row['num_ctx'] for row in contexts if 'num_ctx' in row]
        if sizes:
            num_ctx_counts[str(max(sizes))] += 1
        total_seconds += elapsed
        if error_code is not None:
            errors[error_code] += 1
        else:
            if cascade:
                fallback_counts.update(reasons)
            issues.update(issue.code for issue in result.issues)
            issues_by_field.update(
                issue.code if issue.field is None else f'{issue.code}.{issue.field}'
                for issue in result.issues
            )
            ok += 1
    summary = {
        'files': len(paths),
        'ok': ok,
        'errors': dict(errors),
        'issues': dict(issues),
        'issues_by_field': dict(issues_by_field),
        'total_seconds': total_seconds,
        'average_seconds': total_seconds / len(paths) if paths else 0.0,
        'provider': provider.name,
        'prompt_version': prompt_version(mode, config),
        'mode': mode,
        'whole_fallbacks': issues['WHOLE_TOO_LONG'],
        'llm_calls': len(calls),
        'llm_metrics': metric_summary(calls),
        'ctx_retry': context_counts['ctx_retry'],
        'num_ctx_counts': dict(num_ctx_counts),
    }
    if cascade:
        summary['fallbacks'] = sum(bool(row[4]) for row in results)
        summary['fallback_reasons'] = dict(fallback_counts)
    if only_fallback_candidates is not None:
        summary['selected_fallback_candidates'] = len(paths)
    write_json(target / 'run-summary.json', summary)
    return summary


class _Parser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        """Suppress argparse messages that may echo arbitrary argument values."""
        self.exit(2, 'INVALID_ARGUMENTS\n')


def parser() -> argparse.ArgumentParser:
    """Build the public CLI without exposing the synthetic provider."""
    root = _Parser(prog='cv_compass_llm')
    commands = root.add_subparsers(dest='command', required=True)
    run = commands.add_parser('run')
    run.add_argument('--input', required=True)
    run.add_argument('--output', required=True)
    run.add_argument('--provider')
    run.add_argument('--model')
    run.add_argument('--base-url')
    run.add_argument('--timeout')
    run.add_argument('--mode', metavar='{blocks,whole}')
    run.add_argument('--whole-timeout')
    run.add_argument('--max-blocks')
    run.add_argument('--temperature')
    run.add_argument('--think', choices=('true', 'false'))
    run.add_argument('--num-ctx')
    run.add_argument('--env-file')
    run.add_argument('--limit', type=int)
    run.add_argument('--only-fallback-candidates')
    for name in (
        'name_style',
        'prompt_schema_text',
        'whole_personal',
        'max_output_tokens',
        'keep_alive',
        'ctx_chars_per_token',
        'ctx_fine_tiers',
        'ctx_guard',
    ):
        run.add_argument('--' + name.replace('_', '-'))
    bench = commands.add_parser('bench')
    bench.add_argument('--input', required=True)
    bench.add_argument('--gold', required=True)
    bench.add_argument('--output', required=True)
    bench.add_argument('--config', action='append', required=True)
    bench.add_argument('--repeat', type=int, default=1)
    bench.add_argument('--warmup', type=int, default=0)
    bench.add_argument('--parallel', type=int, default=1)
    bench.add_argument('--json', action='store_true')
    pull = commands.add_parser('pull')
    pull.add_argument('--env-file')
    pull.add_argument('--once', action='store_true')
    pull.add_argument('--log-level')
    for name in (
        'name_style',
        'prompt_schema_text',
        'whole_personal',
        'max_output_tokens',
        'keep_alive',
        'ctx_chars_per_token',
        'ctx_fine_tiers',
        'ctx_guard',
    ):
        pull.add_argument('--' + name.replace('_', '-'))
    check = commands.add_parser('check')
    check.add_argument('--env-file')
    check.add_argument('--log-level')
    draft = commands.add_parser('draft-gold')
    draft.add_argument('--results', required=True)
    draft.add_argument('--output', required=True)
    comparison = commands.add_parser('compare')
    comparison.add_argument('--results', required=True)
    comparison.add_argument('--gold', required=True)
    comparison.add_argument('--json', action='store_true')
    lifecycle = commands.add_parser('lifecycle-report')
    lifecycle.add_argument('--backend-log', required=True)
    lifecycle.add_argument('--ocr-log', required=True)
    lifecycle.add_argument('--llm-log', required=True)
    lifecycle.add_argument('--json', action='store_true')
    return root


def print_metrics(metrics: dict, prefix: str = '') -> None:
    """Print fixed metric names and numeric aggregates only."""
    for key, value in metrics.items():
        name = prefix + key
        if isinstance(value, dict):
            print_metrics(value, name + '.')
        else:
            print(f'{name}={value}')


def create_provider(config: LlmConfig) -> LlmProvider:
    """Share run provider construction with the injectable benchmark factory."""
    _ = config.provenance_model
    options = {}
    if config.max_output_tokens is not None:
        options['num_predict'] = config.max_output_tokens
    if config.keep_alive is not None:
        options['keep_alive'] = config.keep_alive
    if config.ctx_guard:
        options['ctx_guard'] = True
    provider = OllamaProvider(
        config.model,
        base_url=config.base_url,
        timeout_s=config.timeout_s,
        temperature=config.temperature,
        think=config.think,
        num_ctx=config.num_ctx,
        **options,
    )
    provider.extraction_config = config
    if config.fallback_model:
        provider.fallback_provider = create_provider(
            replace(config, model=config.fallback_model, fallback_model='')
        )
    return provider


def main(argv: list[str] | None = None) -> int:
    """Execute a command while keeping paths and source values out of diagnostics."""
    args = parser().parse_args(argv)
    try:
        if args.command == 'bench':
            report = run_bench(
                args.input,
                args.gold,
                args.output,
                args.config,
                repeat=args.repeat,
                warmup=args.warmup,
                parallel=args.parallel,
                provider_factory=create_provider,
            )
            if args.json:
                print(json.dumps(report, allow_nan=False, sort_keys=True))
            else:
                print_bench(report)
            return 0
        if args.command == 'lifecycle-report':
            report = lifecycle_report(args.backend_log, args.ocr_log, args.llm_log)
            if args.json:
                print(json.dumps(report, allow_nan=False, sort_keys=True))
            else:
                print_report(report)
            return 0
        if args.command == 'check':
            return run_check(
                os.environ,
                Path(args.env_file) if args.env_file is not None else None,
                env_file_explicit=args.env_file is not None,
                cli=vars(args),
            )
        if args.command == 'pull':
            config = load_pull_config(
                os.environ,
                Path(args.env_file) if args.env_file is not None else None,
                env_file_explicit=args.env_file is not None,
                cli=vars(args),
            )
            llm = config.llm
            try:
                provider = create_provider(llm)
            except ValueError:
                raise PullConfigError(ENV_KEYS['base_url'], 'OLLAMA_HOST_NOT_LOCAL') from None
            return run_pull(config, provider, once=args.once)
        elif args.command == 'run':
            config = load_config(
                vars(args),
                os.environ,
                Path(args.env_file) if args.env_file is not None else None,
                env_file_explicit=args.env_file is not None,
            )
            provider = create_provider(config)
            summary = run_batch(
                args.input,
                args.output,
                provider,
                max_blocks=config.max_blocks,
                limit=args.limit,
                mode=config.mode,
                whole_timeout=config.whole_timeout,
                **(
                    {'only_fallback_candidates': args.only_fallback_candidates}
                    if args.only_fallback_candidates
                    else {}
                ),
            )
            print(
                f'processed={summary["files"]} ok={summary["ok"]} '
                f'errors={sum(summary["errors"].values())}'
            )
        elif args.command == 'draft-gold':
            print_metrics(draft_gold(args.results, args.output))
        else:
            metrics = compare(args.results, args.gold)
            if args.json:
                print(json.dumps(metrics, allow_nan=False, sort_keys=True))
            else:
                print_metrics(metrics)
    except PullConfigError as error:
        print(str(error))
        return 2
    except ValueError as error:
        print(str(error) if str(error) in CLI_ERRORS else 'INVALID_DATA')
        return 2
    except OSError, RuntimeError:
        print('IO_ERROR')
        return 2
    except Exception:
        print('INTERNAL_ERROR')
        return 2
    return 0
