"""Run from a repository checkout; no global Python installation changes."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / 'src'))
try:
    from cv_compass_worker.__main__ import main
except ImportError:
    import json

    print(json.dumps({'code': 'DEPENDENCY_MISSING', 'delivered': False}), file=sys.stderr)
    raise SystemExit(3) from None
raise SystemExit(main())
