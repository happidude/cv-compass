"""Explicit M7 synthetic supervisor entry point; offline M6 CLI remains separate."""
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent/'src'))
from cv_compass_worker.pull import main

if __name__ == '__main__':
    sys.exit(main())
