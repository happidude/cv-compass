"""Private batch extraction and aggregate-only command line reporting."""

import argparse
import json
import os
from collections import Counter
from pathlib import Path
from time import perf_counter
from typing import Literal

from .batch_io import input_directory, prepare_output, write_json
from .compare import compare
from .config import ENV_KEYS, PullConfigError, load_config, load_pull_config
from .gold import draft_gold
from .ocr_input import load_document
from .pipeline import extract
from .prompts import PROMPT_VERSION
from .providers.base import LlmProvider
from .providers.ollama import OllamaProvider
from .pull import run_pull

INPUT_ERRORS = {
    'INVALID_OCR', 'INVALID_JSON', 'INVALID_UTF8', 'OCR_TOO_LARGE', 'OCR_READ_ERROR',
    'INVALID_PAGE', 'INVALID_PAGE_NUMBER', 'INVALID_PAGE_STATUS', 'INVALID_PAGE_TEXT',
}
CLI_ERRORS = {
    'UNSAFE_OUTPUT', 'OUTPUT_NOT_EMPTY', 'INVALID_INPUT_DIRECTORY', 'INVALID_MAX_BLOCKS',
    'INVALID_LIMIT', 'OLLAMA_HOST_NOT_LOCAL', 'INVALID_MODEL', 'INVALID_TIMEOUT',
    'MISSING_CONFIG', 'INVALID_PROVIDER', 'ENV_FILE_NOT_FOUND', 'INVALID_ENV_FILE',
    'INVALID_TEMPERATURE', 'INVALID_THINK', 'INVALID_NUM_CTX',
}


def run_batch(
    input_dir: str | Path, output_dir: str | Path, provider: LlmProvider, *,
    max_blocks: int = 20, limit: int | None = None,
    repo_root: Path | None | Literal['auto'] = 'auto',
) -> dict:
    """Extract sorted OCR files without modifying any input artifact."""
    if type(max_blocks) is not int or max_blocks < 0:
        raise ValueError('INVALID_MAX_BLOCKS')
    if limit is not None and (type(limit) is not int or limit < 0):
        raise ValueError('INVALID_LIMIT')
    source = input_directory(input_dir)
    paths = sorted(path for path in source.glob('*.json') if path.is_file())
    if limit is not None:
        paths = paths[:limit]
    target = prepare_output(source, output_dir, repo_root=repo_root)
    errors, issues = Counter(), Counter()
    ok, total_seconds = 0, 0.0
    for path in paths:
        started = perf_counter()
        try:
            document = load_document(path)
        except ValueError as error:
            code = str(error) if str(error) in INPUT_ERRORS else 'INVALID_OCR'
            errors[code] += 1
            write_json(target / (path.stem + '.error.json'), {'error': code})
        else:
            result = extract(document, provider, max_blocks=max_blocks)
            payload = result.to_dict()
            payload['seconds'] = perf_counter() - started
            write_json(target / (path.stem + '.result.json'), payload)
            issues.update(issue.code for issue in result.issues)
            ok += 1
        total_seconds += perf_counter() - started
    summary = {
        'files': len(paths), 'ok': ok, 'errors': dict(errors), 'issues': dict(issues),
        'total_seconds': total_seconds,
        'average_seconds': total_seconds / len(paths) if paths else 0.0,
        'provider': provider.name, 'prompt_version': PROMPT_VERSION,
    }
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
    run.add_argument('--max-blocks')
    run.add_argument('--temperature')
    run.add_argument('--think', choices=('true', 'false'))
    run.add_argument('--num-ctx')
    run.add_argument('--env-file')
    run.add_argument('--limit', type=int)
    pull = commands.add_parser('pull')
    pull.add_argument('--env-file')
    pull.add_argument('--once', action='store_true')
    draft = commands.add_parser('draft-gold')
    draft.add_argument('--results', required=True)
    draft.add_argument('--output', required=True)
    comparison = commands.add_parser('compare')
    comparison.add_argument('--results', required=True)
    comparison.add_argument('--gold', required=True)
    comparison.add_argument('--json', action='store_true')
    return root


def print_metrics(metrics: dict, prefix: str = '') -> None:
    """Print fixed metric names and numeric aggregates only."""
    for key, value in metrics.items():
        name = prefix + key
        if isinstance(value, dict):
            print_metrics(value, name + '.')
        else:
            print(f'{name}={value}')


def main(argv: list[str] | None = None) -> int:
    """Execute a command while keeping paths and source values out of diagnostics."""
    args = parser().parse_args(argv)
    try:
        if args.command == 'pull':
            config = load_pull_config(
                os.environ, Path(args.env_file) if args.env_file is not None else None,
                env_file_explicit=args.env_file is not None,
            )
            llm = config.llm
            try:
                provider = OllamaProvider(
                    llm.model, base_url=llm.base_url, timeout_s=llm.timeout_s,
                    temperature=llm.temperature, think=llm.think, num_ctx=llm.num_ctx,
                )
            except ValueError:
                raise PullConfigError(ENV_KEYS['base_url'], 'OLLAMA_HOST_NOT_LOCAL') from None
            return run_pull(config, provider, once=args.once)
        elif args.command == 'run':
            config = load_config(
                vars(args), os.environ,
                Path(args.env_file) if args.env_file is not None else None,
                env_file_explicit=args.env_file is not None,
            )
            provider = OllamaProvider(
                config.model, base_url=config.base_url, timeout_s=config.timeout_s,
                temperature=config.temperature, think=config.think, num_ctx=config.num_ctx,
            )
            summary = run_batch(
                args.input, args.output, provider, max_blocks=config.max_blocks, limit=args.limit,
            )
            print(f'processed={summary["files"]} ok={summary["ok"]} '
                  f'errors={sum(summary["errors"].values())}')
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
    except (OSError, RuntimeError):
        print('IO_ERROR')
        return 2
    except Exception:
        print('INTERNAL_ERROR')
        return 2
    return 0
