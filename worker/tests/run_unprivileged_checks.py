"""Ubuntu subset when Docker control is NOT authorized. Never a full quality gate."""

import argparse
from pathlib import Path
import sys
import unittest
from run_checks import ROOT, run_suite


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--xml', required=True, type=Path)
    args = parser.parse_args()
    loader = unittest.defaultTestLoader
    suite = unittest.TestSuite()
    for module in (
        'test_encryption',
        'test_pipeline',
        'test_additional',
        'test_review_regressions',
        'test_hardening_review',
        'verify_contract',
        'test_stack_smoke',
    ):
        suite.addTests(loader.loadTestsFromName(module))
    for name in (
        'test_hardening_contract.ExitContractTests',
        'test_hardening_contract.AdditionalEngineBoundaries.test_rotations_180_and_270_real_ocr',
        'test_hardening_contract.AdditionalEngineBoundaries.test_nearblank_is_not_silently_blank',
    ):
        suite.addTests(loader.loadTestsFromName(name))
    print(
        'SUBSET ONLY: 13 mandatory Docker-dependent Ubuntu checks BLOCKED/NOT VERIFIED; no full gate claim.',
        flush=True,
    )
    return run_suite(suite, args.xml)


if __name__ == '__main__':
    raise SystemExit(main())
