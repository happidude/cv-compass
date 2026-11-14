"""Private JSON files and resolved output boundary checks."""

import json
from pathlib import Path
from typing import Literal

from .json_strict import strict_json

MAX_JSON_BYTES = 16 * 1024 * 1024


def load_json(path: str | Path, code: str) -> object:
    """Load bounded strict JSON with a fixed error code."""
    try:
        with Path(path).open('rb') as stream:
            data = stream.read(MAX_JSON_BYTES + 1)
        if len(data) > MAX_JSON_BYTES:
            raise ValueError
        return strict_json(data.decode('utf-8'))
    except (OSError, ValueError, UnicodeError):
        raise ValueError(code) from None


def write_json(path: Path, value: dict) -> None:
    """Create a new JSON artifact without replacing existing files."""
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=True, allow_nan=False, indent=2)
        stream.write('\n')


def input_directory(path: str | Path) -> Path:
    """Require a readable directory with a content-free failure."""
    resolved = Path(path).resolve()
    if not resolved.is_dir():
        raise ValueError('INVALID_INPUT_DIRECTORY')
    return resolved


def find_repository() -> Path | None:
    """Find the package repository without relying on the working directory."""
    return next((parent for parent in Path(__file__).resolve().parents
                 if (parent / '.git').exists()), None)


def prepare_output(
    source: Path, output: str | Path, *, allow_existing: bool = False,
    repo_root: Path | None | Literal['auto'] = 'auto',
) -> Path:
    """Keep writes outside the repository and source, failing closed without a repo."""
    target = Path(output).resolve()
    source = source.resolve()
    repo = find_repository() if repo_root == 'auto' else repo_root
    if repo is not None:
        repo = repo.resolve()
    # Reject ancestors too: they can contain the source or repository artifacts.
    if (target == source or source in target.parents or target in source.parents
            or repo is None or target == repo or repo in target.parents):
        raise ValueError('UNSAFE_OUTPUT')
    if target.exists():
        if not target.is_dir():
            raise ValueError('UNSAFE_OUTPUT')
        if not allow_existing and any(target.iterdir()):
            raise ValueError('OUTPUT_NOT_EMPTY')
    target.mkdir(parents=True, exist_ok=True)
    return target
