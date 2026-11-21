"""O11 overflow routing with synthetic documents and in-memory providers only."""

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from test_core import document, personal
from test_o8_cascade import GOOD, NAMED, TEXT, settings
from test_o9_ctx_guard import OVERFLOW

from cv_compass_llm.batch_io import write_json
from cv_compass_llm.cli import run_batch
from cv_compass_llm.pipeline import extract, fallback_reasons
from cv_compass_llm.providers import FakeProvider, ProviderError
from cv_compass_llm.schema import Issue


class OverflowCascadeTests(unittest.TestCase):
    def test_overflow_codes_suppress_quality_reasons_in_any_field_only_with_guard(self):
        doc = document(TEXT)
        poor = extract(doc, FakeProvider([personal(), {'experiences': []}]))
        expected = ('NO_EXPERIENCE', 'NO_FULLNAME')
        for code in ('CONTEXT_OVERFLOW', 'WHOLE_TOO_LONG'):
            for field in (None, 'personal', 'experiences', 'fullName'):
                with self.subTest(code=code, field=field):
                    result = replace(poor, issues=poor.issues + (Issue(code, field),))
                    self.assertEqual(fallback_reasons(result, doc, ctx_guard=True), ())
                    self.assertEqual(fallback_reasons(result, doc, ctx_guard=False), expected)
                    self.assertEqual(fallback_reasons(result, doc), expected)

    def test_exhausted_context_retry_does_not_call_backup(self):
        for unit in ('personal', 'experiences', 'whole'):
            with self.subTest(unit=unit):
                failures = [ProviderError(OVERFLOW), ProviderError(OVERFLOW)]
                responses = (
                    failures + [personal(), GOOD]
                    if unit == 'whole'
                    else failures + [GOOD]
                    if unit == 'personal'
                    else [personal()] + failures
                )
                primary, backup, reasons = FakeProvider(responses), FakeProvider([]), []
                result = extract(
                    document(TEXT),
                    primary,
                    mode='whole' if unit == 'whole' else 'blocks',
                    config=settings(ctx_guard=True, fallback_model='large'),
                    fallback_provider=backup,
                    on_fallback=reasons.append,
                )
                self.assertIn(
                    Issue('WHOLE_TOO_LONG') if unit == 'whole' else Issue('CONTEXT_OVERFLOW', unit),
                    result.issues,
                )
                self.assertEqual(reasons, [()])
                self.assertEqual(backup.calls, [])
                self.assertEqual(len(primary.calls), 4 if unit == 'whole' else 3)

    def test_quality_failure_still_calls_backup_with_guard(self):
        primary, backup, reasons = (
            FakeProvider([personal(), GOOD]),
            FakeProvider([NAMED, GOOD]),
            [],
        )
        result = extract(
            document(TEXT),
            primary,
            config=settings(ctx_guard=True, fallback_model='large'),
            fallback_provider=backup,
            on_fallback=reasons.append,
        )
        self.assertEqual(reasons, [('NO_FULLNAME',)])
        self.assertEqual(len(backup.calls), 2)
        self.assertEqual(result.issues, ())
        self.assertEqual(result.fields[0].key, 'fullName')

    def test_estimated_whole_overflow_keeps_legacy_fallback_when_guard_off(self):
        for guard in (False, True):
            with self.subTest(guard=guard):
                primary = FakeProvider([personal(), GOOD])
                backup, reasons = FakeProvider([NAMED, GOOD]), []
                with patch('cv_compass_llm.pipeline.build_whole_prompt', return_value='x' * 90000):
                    result = extract(
                        document(TEXT),
                        primary,
                        mode='whole',
                        config=settings(ctx_guard=guard, fallback_model='large'),
                        fallback_provider=backup,
                        on_fallback=reasons.append,
                    )
                self.assertEqual(reasons, [() if guard else ('NO_FULLNAME',)])
                self.assertEqual(len(backup.calls), 0 if guard else 2)
                self.assertIn(Issue('WHOLE_TOO_LONG'), result.issues)


class OverflowCandidateFilesTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source, self.previous, self.repo = (
            self.root / name for name in ('ocr', 'previous', 'repo')
        )
        for directory in (self.source, self.previous, self.repo):
            directory.mkdir()

    def save_candidate(self, stem, *, code=None, field=None, candidates=('NO_FULLNAME',)):
        write_json(
            self.source / (stem + '.json'),
            {'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': TEXT}]},
        )
        write_json(
            self.previous / (stem + '.result.json'),
            {
                'fallbackCandidate': list(candidates),
                'issues': [] if code is None else [{'code': code, 'field': field}],
            },
        )

    def test_previous_overflows_are_filtered_only_with_guard(self):
        self.save_candidate('a.cv', code='CONTEXT_OVERFLOW', field='personal')
        self.save_candidate('b', code='WHOLE_TOO_LONG')
        self.save_candidate('c', code='QUOTE_NOT_FOUND', field='company')
        self.save_candidate('d', code='CONTEXT_OVERFLOW', candidates=())
        before = {path: path.read_bytes() for path in self.previous.iterdir()}
        for guard in (False, True):
            with self.subTest(guard=guard):
                cfg = settings(ctx_guard=guard, fallback_model='large')
                provider = FakeProvider([NAMED, GOOD] * 3, extraction_config=cfg)
                provider.fallback_provider = FakeProvider([])
                output = self.root / str(guard)
                summary = run_batch(
                    self.source,
                    output,
                    provider,
                    only_fallback_candidates=self.previous,
                    repo_root=self.repo,
                )
                self.assertEqual(summary['selected_fallback_candidates'], 1 if guard else 3)
                self.assertEqual(len(provider.calls), 2 if guard else 6)
                self.assertEqual(provider.fallback_provider.calls, [])
                self.assertEqual(
                    {path.stem for path in output.glob('*.result.json')},
                    {'c.result'} if guard else {'a.cv.result', 'b.result', 'c.result'},
                )
                saved = json.loads((output / 'run-summary.json').read_text(encoding='utf-8'))
                if guard:
                    self.assertEqual(summary['skipped_overflow'], 2)
                    self.assertEqual(saved['skipped_overflow'], 2)
                else:
                    self.assertNotIn('skipped_overflow', summary)
                    self.assertNotIn('skipped_overflow', saved)
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_guard_selection_always_reports_zero_and_filters_before_limit(self):
        self.save_candidate('a', code='WHOLE_TOO_LONG')
        self.save_candidate('b')
        for limit, skipped in ((None, 1), (0, 1)):
            with self.subTest(limit=limit):
                provider = FakeProvider([NAMED, GOOD])
                provider.fallback_provider = FakeProvider([])
                summary = run_batch(
                    self.source,
                    self.root / f'limited-{limit}',
                    provider,
                    config=settings(ctx_guard=True, fallback_model='large'),
                    only_fallback_candidates=self.previous,
                    limit=limit,
                    repo_root=self.repo,
                )
                self.assertEqual(summary['skipped_overflow'], skipped)
                self.assertEqual(summary['files'], 0 if limit == 0 else 1)
        previous = self.root / 'previous-zero'
        previous.mkdir()
        write_json(previous / 'b.result.json', {'fallbackCandidate': ['NO_FULLNAME'], 'issues': []})
        provider = FakeProvider([NAMED, GOOD])
        provider.fallback_provider = FakeProvider([])
        summary = run_batch(
            self.source,
            self.root / 'zero-overflow',
            provider,
            config=settings(ctx_guard=True, fallback_model='large'),
            only_fallback_candidates=previous,
            repo_root=self.repo,
        )
        self.assertEqual(summary['skipped_overflow'], 0)

    def test_guard_without_backup_keeps_selection_and_output_bytes(self):
        self.save_candidate('a', code='CONTEXT_OVERFLOW', field='experiences')
        self.save_candidate('b', code='WHOLE_TOO_LONG')
        outputs = []
        for guard in (False, True):
            with self.subTest(guard=guard):
                provider = FakeProvider([NAMED, GOOD] * 2)
                output = self.root / f'no-backup-{guard}'
                with patch('cv_compass_llm.cli.perf_counter', return_value=1.0):
                    summary = run_batch(
                        self.source,
                        output,
                        provider,
                        config=settings(ctx_guard=guard),
                        only_fallback_candidates=self.previous,
                        repo_root=self.repo,
                    )
                self.assertEqual(summary['files'], 2)
                self.assertEqual(summary['selected_fallback_candidates'], 2)
                self.assertEqual(len(provider.calls), 4)
                self.assertNotIn('skipped_overflow', summary)
                outputs.append({path.name: path.read_bytes() for path in output.iterdir()})
        self.assertEqual(outputs[0], outputs[1])

    def test_active_filter_rejects_invalid_issues_before_extraction(self):
        self.save_candidate('a')
        invalid_issues = (None, {}, 'CONTEXT_OVERFLOW', 1, [None], ['CONTEXT_OVERFLOW'], [{}, 1])
        for index, issues in enumerate(invalid_issues):
            for candidates in ([], ['NO_FULLNAME']):
                with self.subTest(issues=issues, candidates=candidates):
                    previous = self.root / f'invalid-{index}-{len(candidates)}'
                    previous.mkdir()
                    write_json(
                        previous / 'a.result.json',
                        {'fallbackCandidate': candidates, 'issues': issues},
                    )
                    provider = FakeProvider([])
                    output = self.root / f'rejected-{index}-{len(candidates)}'
                    with self.assertRaisesRegex(ValueError, '^INVALID_FALLBACK_CANDIDATES$'):
                        run_batch(
                            self.source,
                            output,
                            provider,
                            config=settings(ctx_guard=True, fallback_model='large'),
                            only_fallback_candidates=previous,
                            repo_root=self.repo,
                        )
                    self.assertEqual(provider.calls, [])
                    self.assertFalse(output.exists())

    def test_no_backup_preserves_overflow_candidate_metadata_without_new_summary_key(self):
        self.save_candidate('a')
        provider = FakeProvider([ProviderError(OVERFLOW), ProviderError(OVERFLOW), GOOD])
        output = self.root / 'no-backup'
        summary = run_batch(
            self.source,
            output,
            provider,
            config=settings(ctx_guard=True),
            repo_root=self.repo,
        )
        payload = json.loads((output / 'a.result.json').read_text(encoding='utf-8'))
        self.assertEqual(payload['fallbackCandidate'], ['NO_FULLNAME'])
        self.assertIn({'code': 'CONTEXT_OVERFLOW', 'field': 'personal'}, payload['issues'])
        self.assertNotIn('skipped_overflow', summary)
        self.assertNotIn('fallbacks', summary)
        self.assertNotIn('fallback_reasons', summary)


if __name__ == '__main__':
    unittest.main()
