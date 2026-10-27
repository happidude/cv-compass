"""Fresh complete check with JUnit XML, no skip-by-environment shortcuts."""

import argparse
from collections import defaultdict
from pathlib import Path
import sys
import time
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [
    str(ROOT / 'src'),
    str(ROOT / 'tests'),
    str(ROOT / 'design'),
    str(ROOT / 'spikes/m6_1'),
]


class EvidenceResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.records = []

    def startTest(self, test):
        self.started = time.monotonic()
        self.outcome = ('passed', '')
        super().startTest(test)

    def addFailure(self, test, err):
        self.outcome = ('failure', self._exc_info_to_string(err, test))
        super().addFailure(test, err)

    def addError(self, test, err):
        self.outcome = ('error', self._exc_info_to_string(err, test))
        super().addError(test, err)

    def addSkip(self, test, reason):
        self.outcome = ('skipped', reason)
        super().addSkip(test, reason)

    def addSubTest(self, test, subtest, err):
        if err:
            self.outcome = (
                'failure' if issubclass(err[0], test.failureException) else 'error',
                self._exc_info_to_string(err, test),
            )
        super().addSubTest(test, subtest, err)

    def stopTest(self, test):
        self.records.append((test.id(), time.monotonic() - self.started, *self.outcome))
        super().stopTest(test)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--xml', required=True, type=Path)
    args = parser.parse_args()
    loader = unittest.defaultTestLoader
    suite = loader.discover(str(ROOT / 'tests'), pattern='test_*.py')
    import verify_contract
    import test_stack_smoke

    suite.addTests(loader.loadTestsFromModule(verify_contract))
    suite.addTests(loader.loadTestsFromModule(test_stack_smoke))
    return run_suite(suite, args.xml)


def run_suite(suite, xml_path):
    result = unittest.TextTestRunner(verbosity=2, resultclass=EvidenceResult).run(suite)
    suites = ET.Element('testsuites')
    groups = defaultdict(list)
    for record in result.records:
        groups[record[0].rsplit('.', 1)[0]].append(record)
    for name, rows in groups.items():
        counts = {
            outcome: sum(r[2] == outcome for r in rows)
            for outcome in ('failure', 'error', 'skipped')
        }
        node = ET.SubElement(
            suites,
            'testsuite',
            name=name,
            tests=str(len(rows)),
            failures=str(counts['failure']),
            errors=str(counts['error']),
            skipped=str(counts['skipped']),
            time=f'{sum(r[1] for r in rows):.6f}',
        )
        for test_id, duration, outcome, message in rows:
            case = ET.SubElement(
                node,
                'testcase',
                name=test_id.rsplit('.', 1)[1],
                classname=name,
                time=f'{duration:.6f}',
            )
            if outcome != 'passed':
                ET.SubElement(case, outcome).text = message
    xml_path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(suites)
    ET.ElementTree(suites).write(xml_path, encoding='utf-8', xml_declaration=True)
    return 0 if result.wasSuccessful() and not result.skipped else 1


if __name__ == '__main__':
    raise SystemExit(main())
