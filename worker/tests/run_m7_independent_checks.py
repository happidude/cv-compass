"""Explicit non-Docker subset. Full run_checks.py remains mandatory and separate."""
import argparse
import unittest
from pathlib import Path
from run_checks import run_suite


def build_suite():
    loader=unittest.defaultTestLoader
    suite=unittest.TestSuite()
    for module in ('test_encryption','test_pipeline','test_additional','test_review_regressions',
                   'test_hardening_review','test_revision',
                   'test_pull','verify_contract','test_stack_smoke'):
        suite.addTests(loader.loadTestsFromName(module))
    for name in ('test_hardening_contract.ExitContractTests',
                 'test_verification_ownership.VerificationOwnershipTests',
                 'test_hardening_contract.AdditionalEngineBoundaries.test_rotations_180_and_270_real_ocr',
                 'test_hardening_contract.AdditionalEngineBoundaries.test_nearblank_is_not_silently_blank'):
        suite.addTests(loader.loadTestsFromName(name))
    return suite


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--xml',required=True,type=Path)
    args=parser.parse_args()
    print('M7 INDEPENDENT SUBSET ONLY; full Docker isolation / backend-PG-OCR flow BLOCKED/NOT VERIFIED.',flush=True)
    return run_suite(build_suite(),args.xml)


if __name__=='__main__':
    raise SystemExit(main())
