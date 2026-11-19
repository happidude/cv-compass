"""Synthetic cross-process lifecycle timing and privacy acceptance."""

import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timezone
from pathlib import Path

from cv_compass_llm.cli import main
from cv_compass_llm.lifecycle import lifecycle_report

ATTEMPT = 'a0000000-0000-4000-8000-000000000001'
MARKER = 'PRIVATE_SOURCE_SENTINEL'


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.backend, self.ocr, self.llm = (root / name for name in ('backend', 'ocr', 'llm'))
        self.origin = datetime(2026, 11, 19, tzinfo=timezone.utc).timestamp()

    def backend_line(self, seconds, event, document='1', **fields):
        at = datetime.fromtimestamp(self.origin + seconds, timezone.utc).isoformat()
        return f'{at} INFO event={event} documentId={document} ' + ' '.join(
            f'{key}={value}' for key, value in fields.items()
        )

    def worker_line(self, seconds, event, **fields):
        return 'timestamp=' + self.backend_line(seconds, event, **fields).replace(' INFO ', ' ')

    def llm_line(self, at_s, **fields):
        # The real progress formatter uses the host local timezone without an offset.
        at = datetime.fromtimestamp(self.origin + at_s).strftime('%Y-%m-%d %H:%M:%S')
        return f'{at} INFO event=extraction_block attempt={ATTEMPT[:8]} ' + ' '.join(
            f'{key}={value}' for key, value in fields.items()
        )

    def save(self, backend, ocr=(), llm=()):
        for path, rows in ((self.backend, backend), (self.ocr, ocr), (self.llm, llm)):
            path.write_text('\n'.join(rows) + '\n', encoding='utf-8')

    def report(self):
        return lifecycle_report(self.backend, self.ocr, self.llm)

    def fixture(self):
        backend = [
            self.backend_line(0, 'DOCUMENT_STORED'),
            self.backend_line(2, 'OCR_ENQUEUED', jobId=10),
            self.backend_line(5, 'OCR_CLAIMED', jobId=10),
            self.backend_line(16, 'MAPPING_ENQUEUED', jobId=20),
            self.backend_line(20, 'MAPPING_REMOTE_CLAIMED', jobId=20, attemptId=ATTEMPT),
            self.backend_line(31, 'MAPPING_REMOTE_ACCEPTED', jobId=20, attemptId=ATTEMPT),
            self.backend_line(35, 'CANDIDATE_PUBLICATION_RECORDED', jobId=20, status='PUBLISHED'),
        ]
        ocr = [
            self.worker_line(6, 'PIPELINE_STARTED', jobId=10),
            self.worker_line(9, 'PAGE_FINISHED', jobId=10, pageNumber=1, durationMs=3000),
            self.worker_line(15, 'PIPELINE_FINISHED', jobId=10, durationMs=9000),
        ]
        llm = [
            self.llm_line(
                30,
                block='1/1',
                seconds=10,
                prompt_tokens=100,
                output_tokens=40,
                prompt_s='2.000',
                output_s='8.000',
                load_s='0.000',
                tok_s='5.00',
            )
        ]
        return backend, ocr, llm

    def test_each_interval_and_call_are_joined_across_real_formats(self):
        backend, ocr, llm = self.fixture()
        self.save(list(reversed(backend)), list(reversed(ocr)), llm)
        report = self.report()
        row = report['documents'][0]
        self.assertEqual((row['ocr_wait_s'], row['ocr_s'], row['total_s']), (3, 9, 35))
        self.assertEqual(row['mappings'], [{'jobId': '20', 'wait_s': 4, 'publish_wait_s': 4}])
        self.assertEqual(
            row['calls'][0],
            {
                'attemptId': ATTEMPT,
                'block': 1,
                'seconds': 10,
                'prompt_tokens': 100,
                'output_tokens': 40,
                'prompt_s': 2,
                'output_s': 8,
                'load_s': 0,
                'tok_s': 5,
            },
        )
        self.assertEqual(row['pages'], [{'jobId': '10', 'page': 1, 'seconds': 3}])
        self.assertEqual(report['summary']['completed'], 1)
        self.assertAlmostEqual(report['summary']['cv_per_hour'], 3600 / 35)

    def test_worker_context_after_event_preserves_lifecycle_report(self):
        backend, ocr, llm = self.fixture()
        self.save(backend, ocr, llm)
        expected = self.report()
        llm = [
            line.replace('event=extraction_block ', 'event=extraction_block worker=host-42 ')
            for line in llm
        ]
        self.save(backend, ocr, llm)
        self.assertEqual(self.report(), expected)

    def test_percentiles_mean_and_throughput_count_each_document_once(self):
        backend = []
        for document, seconds in (('1', 10), ('2', 20), ('3', 30), ('4', 40)):
            backend += [
                self.backend_line(0, 'DOCUMENT_STORED', document),
                self.backend_line(
                    seconds,
                    'CANDIDATE_PUBLICATION_RECORDED',
                    document,
                    status='PROPOSED',
                ),
            ]
        backend.append(self.backend_line(50, 'CANDIDATE_PUBLICATION_RECORDED', status='PUBLISHED'))
        self.save(backend)
        report = self.report()
        self.assertEqual(
            report['summary']['total_s'],
            {'count': 4, 'average': 25, 'p50': 25, 'p90': 37},
        )
        self.assertEqual(report['summary']['cv_per_hour'], 360)

    def test_bad_lines_are_counted_and_source_values_never_reach_either_output(self):
        backend, ocr, llm = self.fixture()
        backend += [
            MARKER,
            self.backend_line(1, 'OTHER', document=MARKER, rawText=MARKER),
        ]
        ocr += [MARKER]
        llm += [
            MARKER,
            self.llm_line(32, block='1/1', seconds='nan', output_tokens=MARKER),
        ]
        self.save(backend, ocr, llm)
        report = self.report()
        self.assertEqual(report['ignored_lines'], {'backend': 1, 'ocr': 1, 'llm': 2})
        self.assertNotIn(MARKER, json.dumps(report))
        for flag in ([], ['--json']):
            out = io.StringIO()
            with redirect_stdout(out):
                result = main(
                    [
                        'lifecycle-report',
                        '--backend-log',
                        str(self.backend),
                        '--ocr-log',
                        str(self.ocr),
                        '--llm-log',
                        str(self.llm),
                        *flag,
                    ]
                )
            self.assertEqual(result, 0)
            self.assertNotIn(MARKER, out.getvalue())
            self.assertNotIn(str(self.temp.name), out.getvalue())
            self.assertIn('prompt_tokens', out.getvalue())
            if flag:
                self.assertEqual(json.loads(out.getvalue()), report)

    def test_prefix_collisions_and_unknown_attempts_do_not_guess_identity(self):
        backend, ocr, llm = self.fixture()
        backend.append(
            self.backend_line(
                21,
                'MAPPING_REMOTE_CLAIMED',
                '2',
                jobId=21,
                attemptId='a0000000-0000-4000-8000-000000000002',
            )
        )
        self.save(
            backend,
            ocr,
            [*llm, self.llm_line(31, seconds=1).replace('a0000000', 'ffffffff')],
        )
        report = self.report()
        self.assertEqual(report['unmatched_llm'], 2)
        self.assertTrue(all(row['calls'] == [] for row in report['documents']))

    def test_missing_stages_empty_files_and_skipped_publication_are_not_zeroes(self):
        self.save(
            [
                self.backend_line(0, 'DOCUMENT_STORED'),
                self.backend_line(10, 'CANDIDATE_PUBLICATION_RECORDED', status='SKIPPED'),
            ]
        )
        report = self.report()
        row = report['documents'][0]
        self.assertEqual(row['skipped'], 1)
        self.assertIsNone(row['total_s'])
        self.assertIsNone(row['ocr_s'])
        self.assertEqual(report['summary']['completed'], 0)
        self.assertIsNone(report['summary']['total_s']['p90'])
        self.save([], [], [])
        self.assertEqual(self.report()['documents'], [])

    def test_ocr_attempt_prefix_cannot_identify_an_llm_call(self):
        self.save(
            [self.backend_line(5, 'OCR_CLAIMED', jobId=10, attemptId=ATTEMPT)],
            [],
            [self.llm_line(10, block='1/1', seconds=2)],
        )
        report = self.report()
        self.assertEqual(report['unmatched_llm'], 1)
        self.assertEqual(report['documents'][0]['calls'], [])

    def test_ocr_prefix_collision_does_not_hide_a_remote_mapping_attempt(self):
        backend, ocr, llm = self.fixture()
        backend.append(
            self.backend_line(
                5,
                'OCR_CLAIMED',
                '2',
                jobId=11,
                attemptId='a0000000-0000-4000-8000-000000000002',
            )
        )
        self.save(backend, ocr, llm)
        report = self.report()
        self.assertEqual(report['unmatched_llm'], 0)
        documents = {row['documentId']: row for row in report['documents']}
        self.assertEqual(documents['1']['calls'][0]['attemptId'], ATTEMPT)
        self.assertEqual(documents['2']['calls'], [])

    def test_ocr_retries_sum_durations_and_mapping_jobs_keep_their_own_clocks(self):
        backend, ocr, llm = self.fixture()
        backend += [
            self.backend_line(40, 'MAPPING_ENQUEUED', jobId=21),
            self.backend_line(43, 'MAPPING_CLAIMED', jobId=21),
            self.backend_line(48, 'MAPPING_COMPLETED', jobId=21),
            self.backend_line(50, 'CANDIDATE_PUBLICATION_RECORDED', jobId=21, status='PROPOSED'),
        ]
        ocr += [
            self.worker_line(16, 'PIPELINE_STARTED', jobId=10),
            self.worker_line(18, 'PIPELINE_FINISHED', jobId=10, durationMs=2000),
        ]
        self.save(backend, ocr, llm)
        row = self.report()['documents'][0]
        self.assertEqual(row['ocr_s'], 11)
        self.assertEqual(row['mappings'][1], {'jobId': '21', 'wait_s': 3, 'publish_wait_s': 2})

    def test_io_failures_do_not_echo_paths(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = main(
                [
                    'lifecycle-report',
                    '--backend-log',
                    str(self.backend),
                    '--ocr-log',
                    str(self.ocr),
                    '--llm-log',
                    str(self.llm),
                ]
            )
        self.assertEqual((code, out.getvalue()), (2, 'IO_ERROR\n'))

    def test_ocr_missing_duration_and_start_stays_unavailable(self):
        self.save(
            [self.backend_line(0, 'DOCUMENT_STORED')],
            [self.worker_line(10, 'PIPELINE_FINISHED', jobId=10)],
        )
        row = self.report()['documents'][0]
        self.assertIsNone(row['ocr_s'])
        self.assertIsNone(row['ocr_runs'][0]['seconds'])
