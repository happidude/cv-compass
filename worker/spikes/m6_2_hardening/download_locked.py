"""Provision-only wheel download using official PyPI and the checked-in hashes."""

from pathlib import Path
import socket
import sys

original = socket.getaddrinfo
socket.getaddrinfo = lambda host, port, family=0, type=0, proto=0, flags=0: original(
    host, port, socket.AF_INET, type, proto, flags
)
from pip._internal.cli.main import main

root = Path(sys.argv[1])
destination = sys.argv[2]
raise SystemExit(
    main(
        [
            'download',
            '--index-url',
            'https://pypi.org/simple',
            '--timeout',
            '30',
            '--retries',
            '1',
            '--only-binary=:all:',
            '--require-hashes',
            '-r',
            str(root / 'requirements.lock'),
            '-r',
            str(root / 'requirements-test.lock'),
            '--dest',
            destination,
        ]
    )
)
