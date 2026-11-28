"""Synthetic batch, human gold and aggregate privacy acceptance tests."""

import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from cv_compass_llm.batch_io import find_repository, prepare_output
from cv_compass_llm.cli import main, run_batch
from cv_compass_llm.compare import compare
from cv_compass_llm.gold import (
    PERSONAL_FIELDS,
    SCHEMA_VERSION,
    draft_gold,
    group_experiences,
    load_gold,
)
from cv_compass_llm.prompts import PROMPT_VERSION
from cv_compass_llm.providers import FakeProvider, ProviderError


def save(path: Path, data: object) -> None:
    """Write a synthetic JSON fixture."""
    path.write_text(json.dumps(data), encoding='utf-8')


def read(path: Path) -> dict:
    """Read a test artifact for structural assertions."""
    return json.loads(path.read_text(encoding='utf-8'))


def gold_record(**personal: str | None) -> dict:
    """Build a complete confirmed synthetic gold record."""
    return {
        'status': 'CONFIRMED',
        'personal': {key: personal.get(key) for key in PERSONAL_FIELDS},
        'experiences': [],
    }


def result_record(personal: dict, experiences: list[dict], seconds: float = 2.0) -> dict:
    """Build a result with private evidence that must never reach reports."""
    return {
        'fields': [
            {'key': key, 'value': value, 'evidence': {'text': 'EVIDENCE_MARKER'}}
            for key, value in personal.items()
            if value is not None
        ],
        'experiences': [
            dict(item, evidence={'text': 'EXPERIENCE_EVIDENCE_MARKER'}) for item in experiences
        ],
        'issues': [{'code': 'SYNTHETIC_ISSUE'}],
        'seconds': seconds,
    }


def experience(title: str, company: str, start: str, end: str | None, ongoing: bool) -> dict:
    """Build a value-only synthetic employment record."""
    return dict(title=title, company=company, start=start, end=end, ongoing=ongoing)


class DirectoryTests(unittest.TestCase):
    def setUp(self) -> None:
        """Use independent temporary folders outside the package repository."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        environment = {
            key: value for key, value in os.environ.items() if not key.startswith('CVC_LLM_')
        }
        self.environment = patch.dict(os.environ, environment, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.env_file = patch(
            'cv_compass_llm.config.default_env_file', return_value=self.root / '.env'
        )
        self.env_file.start()
        self.addCleanup(self.env_file.stop)
        self.source = self.root / 'input'
        self.source.mkdir()
        self.output = self.root / 'output'
        self.repo = self.root / 'synthetic-repository'
        self.repo.mkdir()
        (self.repo / '.git').mkdir()


class PathSafetyTests(DirectoryTests):
    def test_repository_discovery_uses_package_location(self) -> None:
        """Discover a temporary repository and fail closed outside its tree."""
        with patch('cv_compass_llm.batch_io.__file__', str(self.repo / 'package' / 'io.py')):
            self.assertEqual(find_repository(), self.repo.resolve())
        isolated = self.root / 'isolated'
        isolated.mkdir()
        with (
            patch('cv_compass_llm.batch_io.__file__', str(isolated / 'io.py')),
            patch.object(Path, 'exists', return_value=False),
        ):
            self.assertIsNone(find_repository())

    def test_explicit_missing_repository_fails_closed(self) -> None:
        """Reject all write operations when no repository boundary is available."""
        with self.assertRaisesRegex(ValueError, '^UNSAFE_OUTPUT$'):
            prepare_output(self.source, self.output, repo_root=None)
        with self.assertRaisesRegex(ValueError, '^UNSAFE_OUTPUT$'):
            run_batch(self.source, self.output, FakeProvider([]), repo_root=None)
        with self.assertRaisesRegex(ValueError, '^UNSAFE_OUTPUT$'):
            draft_gold(self.source, self.output, repo_root=None)
        self.assertFalse(self.output.exists())

    def test_cli_without_repository_fails_closed(self) -> None:
        """Keep the real CLI fail-closed without introducing an environment override."""
        commands = (
            [
                'run',
                '--input',
                str(self.source),
                '--output',
                str(self.output),
                '--provider',
                'ollama',
                '--model',
                'synthetic',
            ],
            ['draft-gold', '--results', str(self.source), '--output', str(self.output)],
        )
        for argv in commands:
            out, err = io.StringIO(), io.StringIO()
            with (
                patch('cv_compass_llm.batch_io.find_repository', return_value=None),
                redirect_stdout(out),
                redirect_stderr(err),
            ):
                code = main(argv)
            self.assertEqual(code, 2)
            self.assertEqual(out.getvalue() + err.getvalue(), 'UNSAFE_OUTPUT\n')
            self.assertFalse(self.output.exists())

    @unittest.skipUnless(os.name == 'nt', 'Windows path case semantics')
    def test_output_matching_input_with_different_case_is_rejected(self) -> None:
        """Reject an input alias that differs only in Windows path casing."""
        alias = self.source.with_name(self.source.name.upper())
        with self.assertRaisesRegex(ValueError, '^UNSAFE_OUTPUT$'):
            run_batch(self.source, alias, FakeProvider([]), repo_root=self.repo)
        with self.assertRaisesRegex(ValueError, '^UNSAFE_OUTPUT$'):
            draft_gold(self.source, alias, repo_root=self.repo)


class BatchTests(DirectoryTests):
    def write_ocr(self, name: str, text: str) -> None:
        """Write a successful synthetic OCR page."""
        save(
            self.source / name,
            {
                'pages': [
                    {'page_number': 1, 'status': 'ok', 'raw_text': text},
                ]
            },
        )

    def test_batch_outputs_summary_and_preserves_input(self) -> None:
        """Continue past invalid input and retain byte-identical source files."""
        self.write_ocr('a.json', 'SYNTHETIC_NAME_MARKER\nExperience\n')
        (self.source / 'b.json').write_bytes(b'{PRIVATE_INVALID_MARKER')
        self.write_ocr('c.json', 'Experience\n')
        (self.source / 'ignore.txt').write_bytes(b'untouched')
        nested = self.source / 'nested'
        nested.mkdir()
        save(nested / 'ignored.json', {})
        before = {
            path.relative_to(self.source): path.read_bytes()
            for path in self.source.rglob('*')
            if path.is_file()
        }
        provider = FakeProvider(
            [
                {
                    'personal': {
                        key: (
                            {'value': 'SYNTHETIC_NAME_MARKER', 'quote': 'SYNTHETIC_NAME_MARKER'}
                            if key == 'fullName'
                            else None
                        )
                        for key in (*PERSONAL_FIELDS, 'summary')
                    }
                },
                {'personal': dict.fromkeys((*PERSONAL_FIELDS, 'summary'))},
            ]
        )
        with patch('cv_compass_llm.cli.perf_counter', side_effect=range(9)):
            summary = run_batch(self.source, self.output, provider, repo_root=self.repo)
        self.assertEqual(summary['files'], 3)
        self.assertEqual(summary['ok'], 2)
        self.assertEqual(summary['errors'], {'INVALID_JSON': 1})
        self.assertEqual(summary['issues'], {'PERSONAL_EMPTY': 1})
        self.assertEqual(summary['issues_by_field'], {'PERSONAL_EMPTY': 1})
        self.assertEqual(summary['provider'], 'fake')
        self.assertEqual(summary['prompt_version'], PROMPT_VERSION)
        self.assertEqual(summary['mode'], 'blocks')
        self.assertEqual(summary['whole_fallbacks'], 0)
        self.assertEqual(read(self.output / 'run-summary.json'), summary)
        result = read(self.output / 'a.result.json')
        self.assertEqual(result['fields'][0]['value'], 'SYNTHETIC_NAME_MARKER')
        self.assertEqual(result['seconds'], 1)
        self.assertEqual(read(self.output / 'b.error.json'), {'error': 'INVALID_JSON'})
        self.assertEqual(
            {
                path.relative_to(self.source): path.read_bytes()
                for path in self.source.rglob('*')
                if path.is_file()
            },
            before,
        )
        self.assertEqual(
            {path.name for path in self.output.iterdir()},
            {
                'a.result.json',
                'b.error.json',
                'c.result.json',
                'run-summary.json',
            },
        )

    def test_sorted_limit_and_issue_counts(self) -> None:
        """Apply a deterministic filename limit and aggregate pipeline issues."""
        self.write_ocr('z.json', 'UNSELECTED_MARKER')
        self.write_ocr('a.json', 'SELECTED_MARKER\nExperience\n')
        provider = FakeProvider([ProviderError('PRIVATE_PROVIDER_MARKER')])
        summary = run_batch(self.source, self.output, provider, limit=1, repo_root=self.repo)
        self.assertEqual(summary['files'], 1)
        self.assertEqual(summary['issues'], {'PROVIDER_ERROR': 1})
        self.assertEqual(summary['issues_by_field'], {'PROVIDER_ERROR.personal': 1})
        self.assertIn('SELECTED_MARKER', provider.prompts[0])
        self.assertNotIn('UNSELECTED_MARKER', provider.prompts[0])
        self.assertFalse((self.output / 'z.result.json').exists())

    def test_output_boundaries(self) -> None:
        """Reject repository, input, descendants and source ancestors before writes."""
        repo_output = self.repo / 'forbidden-output'
        for output in (self.repo, repo_output, self.source, self.source / 'child', self.root):
            with self.subTest(output=output):
                with self.assertRaisesRegex(ValueError, '^UNSAFE_OUTPUT$'):
                    run_batch(self.source, output, FakeProvider([]), repo_root=self.repo)
        self.assertFalse(repo_output.exists())
        self.assertFalse((self.source / 'child').exists())

    def test_existing_output_is_never_modified(self) -> None:
        """Reject nonempty output without overwriting its artifact."""
        self.output.mkdir()
        marker = self.output / 'keep.json'
        marker.write_bytes(b'KEEP_MARKER')
        with self.assertRaisesRegex(ValueError, '^OUTPUT_NOT_EMPTY$'):
            run_batch(self.source, self.output, FakeProvider([]), repo_root=self.repo)
        self.assertEqual(marker.read_bytes(), b'KEEP_MARKER')

    def test_empty_batch_and_invalid_options(self) -> None:
        """Handle zero files and validate options before creating output."""
        for kwargs, code in (
            ({'limit': -1}, 'INVALID_LIMIT'),
            ({'limit': True}, 'INVALID_LIMIT'),
            ({'max_blocks': -1}, 'INVALID_MAX_BLOCKS'),
        ):
            with self.assertRaisesRegex(ValueError, '^' + code + '$'):
                run_batch(self.source, self.output, FakeProvider([]), repo_root=self.repo, **kwargs)
            self.assertFalse(self.output.exists())
        summary = run_batch(
            self.source, self.output, FakeProvider([]), limit=0, repo_root=self.repo
        )
        self.assertEqual(summary['average_seconds'], 0)
        self.assertEqual(summary['ok'], 0)

    def test_cli_diagnostics_never_echo_arguments(self) -> None:
        """Keep arbitrary argument values and filesystem paths out of diagnostics."""
        for argv in (['run', '--provider', 'PRIVATE_ARGUMENT_MARKER'], ['PRIVATE_COMMAND_MARKER']):
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                with self.assertRaises(SystemExit) as caught:
                    main(argv)
            self.assertEqual(caught.exception.code, 2)
            self.assertEqual(out.getvalue() + err.getvalue(), 'INVALID_ARGUMENTS\n')
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = main(
                [
                    'run',
                    '--input',
                    str(self.source),
                    '--output',
                    str(self.output),
                    '--provider',
                    'ollama',
                    '--model',
                    'synthetic',
                    '--timeout',
                    'PRIVATE',
                ]
            )
        self.assertEqual(code, 2)
        self.assertEqual(out.getvalue() + err.getvalue(), 'INVALID_TIMEOUT\n')
        out = io.StringIO()
        with redirect_stdout(out):
            code = main(
                [
                    'run',
                    '--input',
                    str(self.source),
                    '--output',
                    str(self.output),
                    '--provider',
                    'ollama',
                    '--model',
                    'synthetic',
                    '--base-url',
                    'http://PRIVATE.invalid',
                ]
            )
        self.assertEqual(code, 2)
        self.assertEqual(out.getvalue(), 'OLLAMA_HOST_NOT_LOCAL\n')

    def test_cli_run_completes_with_file_errors(self) -> None:
        """Return success and counts when a local batch includes malformed OCR."""
        self.write_ocr('a.json', 'PRIVATE_VALUE_MARKER\nExperience\n')
        (self.source / 'b.json').write_bytes(b'PRIVATE_INVALID_MARKER')
        response = json.dumps({'personal': dict.fromkeys((*PERSONAL_FIELDS, 'summary'))})
        out, err = io.StringIO(), io.StringIO()
        with (
            patch('cv_compass_llm.providers.OllamaProvider.generate', return_value=response),
            patch('cv_compass_llm.batch_io.find_repository', return_value=self.repo),
            redirect_stdout(out),
            redirect_stderr(err),
        ):
            code = main(
                [
                    'run',
                    '--input',
                    str(self.source),
                    '--output',
                    str(self.output),
                    '--provider',
                    'ollama',
                    '--model',
                    'synthetic-model',
                ]
            )
        self.assertEqual(code, 0)
        self.assertEqual(out.getvalue(), 'processed=2 ok=1 errors=1\n')
        self.assertEqual(err.getvalue(), '')
        self.assertEqual(
            read(self.output / 'run-summary.json')['provider'], 'ollama:synthetic-model'
        )

    def test_unexpected_core_exceptions_are_private(self) -> None:
        """Replace unexpected core failures with a fixed diagnostic in every command."""
        commands = (
            (
                'run_batch',
                [
                    'run',
                    '--input',
                    str(self.source),
                    '--output',
                    str(self.output),
                    '--provider',
                    'ollama',
                    '--model',
                    'synthetic',
                ],
            ),
            (
                'draft_gold',
                ['draft-gold', '--results', str(self.source), '--output', str(self.output)],
            ),
            ('compare', ['compare', '--results', str(self.source), '--gold', str(self.output)]),
        )
        for function, argv in commands:
            out, err = io.StringIO(), io.StringIO()
            with (
                patch(
                    'cv_compass_llm.cli.' + function,
                    side_effect=TypeError('zq-private-exception-marker-7'),
                ),
                redirect_stdout(out),
                redirect_stderr(err),
            ):
                code = main(argv)
            self.assertEqual(code, 2)
            self.assertEqual(out.getvalue(), 'INTERNAL_ERROR\n')
            self.assertEqual(err.getvalue(), '')


class GoldTests(DirectoryTests):
    def test_draft_values_and_no_overwrite(self) -> None:
        """Drop evidence and issues and preserve an existing confirmed gold."""
        personal = gold_record(fullName='DRAFT_VALUE_MARKER')['personal']
        item = experience('ROLE_MARKER', 'ORG_MARKER', '2020', None, True)
        save(self.source / 'a.result.json', result_record(personal, [item]))
        self.output.mkdir()
        existing = self.output / 'b.gold.json'
        save(existing, gold_record(fullName='CONFIRMED_VALUE_MARKER'))
        original = existing.read_bytes()
        (self.source / 'b.result.json').write_bytes(b'not read when gold exists')
        (self.source / 'c.result.json').write_bytes(b'invalid')
        counts = draft_gold(self.source, self.output, repo_root=self.repo)
        self.assertEqual(counts, {'created': 1, 'skipped_existing': 1, 'errors': 1})
        self.assertEqual(
            read(self.output / 'a.gold.json'),
            {
                'schemaVersion': SCHEMA_VERSION,
                'status': 'DRAFT',
                'personal': personal,
                'experiences': group_experiences([item]),
            },
        )
        self.assertEqual(existing.read_bytes(), original)
        counts = draft_gold(self.source, self.output, repo_root=self.repo)
        self.assertEqual(counts['skipped_existing'], 2)

    def test_gold_strict_validation(self) -> None:
        """Accept only exact gold keys, nullable strings and boolean ongoing."""
        path = self.source / 'fixture.gold.json'
        valid = gold_record(fullName='SYNTHETIC_MARKER')
        valid['experiences'] = [experience('ROLE', 'ORG', '2020', None, True)]
        for status in ('DRAFT', 'CONFIRMED'):
            valid['status'] = status
            save(path, valid)
            self.assertEqual(
                load_gold(path),
                dict(
                    valid,
                    schemaVersion=SCHEMA_VERSION,
                    experiences=group_experiences(valid['experiences']),
                ),
            )
        invalid = [None, [], {}, dict(valid, status='draft'), dict(valid, extra=True)]
        for section, key, value in (
            ('personal', 'fullName', 1),
            ('personal', 'gender', False),
            ('experiences', 'ongoing', 'true'),
            ('experiences', 'title', 1),
        ):
            item = copy.deepcopy(valid)
            target = item[section][0] if section == 'experiences' else item[section]
            target[key] = value
            invalid.append(item)
        missing = copy.deepcopy(valid)
        del missing['personal']['location']
        invalid.append(missing)
        for data in invalid:
            save(path, data)
            with self.assertRaisesRegex(ValueError, '^INVALID_GOLD$'):
                load_gold(path)
        for raw in (b'{', b'{"status":"DRAFT","status":"CONFIRMED"}', b'\xff'):
            path.write_bytes(raw)
            with self.assertRaisesRegex(ValueError, '^INVALID_GOLD$'):
                load_gold(path)
        with self.assertRaisesRegex(ValueError, '^INVALID_GOLD$'):
            load_gold(self.source / 'absent.gold.json')

    def test_draft_uses_same_path_safety(self) -> None:
        """Reject gold output in the repository or inside results."""
        for output in (self.repo, self.repo / 'forbidden-gold', self.source / 'gold'):
            with self.assertRaisesRegex(ValueError, '^UNSAFE_OUTPUT$'):
                draft_gold(self.source, output, repo_root=self.repo)

    def test_draft_cli_only_prints_counts(self) -> None:
        """Keep draft values and filenames out of CLI output."""
        save(
            self.source / 'PRIVATE_FILE_MARKER.result.json',
            result_record({'fullName': 'PRIVATE_VALUE_MARKER'}, []),
        )
        out, err = io.StringIO(), io.StringIO()
        with (
            patch('cv_compass_llm.batch_io.find_repository', return_value=self.repo),
            redirect_stdout(out),
            redirect_stderr(err),
        ):
            code = main(['draft-gold', '--results', str(self.source), '--output', str(self.output)])
        self.assertEqual(code, 0)
        self.assertEqual(out.getvalue(), 'created=1\nskipped_existing=0\nerrors=0\n')
        self.assertEqual(err.getvalue(), '')


class CompareTests(DirectoryTests):
    def fixtures(self) -> None:
        """Build hand-calculated pairs covering every personal category."""
        self.output.mkdir()
        first = gold_record(fullName='MARKÉR_NAME', gender='FEMALE', location='CITY_MARKER')
        a = experience('zq-marker-title-7', 'zq-marker-company-7', '2020', '2021', False)
        b = experience('zq-marker-title-8', 'zq-marker-company-8', '2022', None, True)
        first['experiences'] = [a, b]
        save(self.output / 'zq-marker-document-7.gold.json', first)
        predicted = dict(a, title='  ZQ-MARKER-TITLE-7\t', company='ZQ-MARKER-COMPANY-7')
        save(
            self.source / 'zq-marker-document-7.result.json',
            result_record(
                {
                    'fullName': '  marke\u0301r_name\t',
                    'gender': 'OTHER',
                    'dateOfBirth': '2001',
                },
                [
                    predicted,
                    dict(b, company='WRONG_ORG_MARKER'),
                    experience('EXTRA_ROLE_MARKER', 'EXTRA_ORG_MARKER', '2019', '2019', False),
                ],
            ),
        )
        second = gold_record(
            fullName='MISSING_NAME_MARKER', location='CITY_MARKER', dateOfBirth='2000'
        )
        second['experiences'] = [a]
        save(self.output / 'zq-marker-document-8.gold.json', second)
        save(
            self.source / 'zq-marker-document-8.result.json',
            result_record(
                {
                    'location': '\n city_marker   ',
                    'dateOfBirth': '2001',
                },
                [],
                4,
            ),
        )
        save(self.output / 'zq-marker-draft-7.gold.json', dict(first, status='DRAFT'))

    def test_hand_calculated_metrics(self) -> None:
        """Compute normalized personal counts and per-document multiset matches."""
        self.fixtures()
        metrics = compare(self.source, self.output)
        self.assertEqual(metrics['compared'], 2)
        self.assertEqual(metrics['skipped_draft'], 1)
        self.assertEqual(metrics['invalid_gold'], 0)
        self.assertEqual(metrics['invalid_results'], 0)
        self.assertEqual(metrics['unpaired_results'], 0)
        self.assertEqual(metrics['average_seconds'], 3)
        expected_personal = {
            'fullName': {'correct': 1, 'wrong': 0, 'missing': 1, 'extra': 0},
            'gender': {'correct': 1, 'wrong': 1, 'missing': 0, 'extra': 0},
            'location': {'correct': 1, 'wrong': 0, 'missing': 1, 'extra': 0},
            'dateOfBirth': {'correct': 0, 'wrong': 1, 'missing': 0, 'extra': 1},
        }
        for counts in expected_personal.values():
            counts['loose'] = dict(counts)
        expected_personal['fullName']['core'] = {
            'correct': 1,
            'wrong': 0,
            'missing': 1,
            'extra': 0,
        }
        self.assertEqual(metrics['personal'], expected_personal)
        for kind in ('companies', 'companyPeriods', 'titles', 'positions'):
            self.assertEqual(
                metrics['experiences'][kind],
                {
                    'matched': 1,
                    'predicted': 3,
                    'gold': 3,
                    'precision': 1 / 3,
                    'recall': 1 / 3,
                    'loose': {
                        'matched': 1,
                        'predicted': 3,
                        'gold': 3,
                        'precision': 1 / 3,
                        'recall': 1 / 3,
                    },
                    **{
                        level: {
                            'matched': 1,
                            'predicted': 3,
                            'gold': 3,
                            'precision': 1 / 3,
                            'recall': 1 / 3,
                        }
                        for level in ('core', 'coreYear')
                        if (level == 'core' and kind != 'companyPeriods')
                        or (level == 'coreYear' and kind == 'positions')
                    },
                },
            )

    def test_cli_only_prints_aggregates(self) -> None:
        """Keep all private markers, filenames and evidence out of both formats."""
        self.fixtures()
        markers = {str(self.source), str(self.output)}

        def collect_values(value: object) -> None:
            """Collect every string value rather than comparing ambiguous substrings."""
            if isinstance(value, str):
                markers.add(value)
            elif isinstance(value, dict):
                for item in value.values():
                    collect_values(item)
            elif isinstance(value, list):
                for item in value:
                    collect_values(item)

        for directory in (self.source, self.output):
            for path in directory.glob('*.json'):
                markers.add(path.name)
                markers.add(path.name.split('.')[0])
                collect_values(read(path))
        markers.difference_update(
            {
                'fullName',
                'gender',
                'location',
                'dateOfBirth',
                'title',
                'company',
                'start',
                'end',
                'ongoing',
                'personal',
                'experiences',
                'status',
                'CONFIRMED',
                'DRAFT',
            }
        )
        self.assertTrue(
            {
                'MARKÉR_NAME',
                '  marke\u0301r_name\t',
                'FEMALE',
                'OTHER',
                'CITY_MARKER',
                '\n city_marker   ',
                'MISSING_NAME_MARKER',
                '2000',
                '2001',
                'zq-marker-title-7',
                'zq-marker-company-7',
                'zq-marker-title-8',
                'zq-marker-company-8',
                '  ZQ-MARKER-TITLE-7\t',
                'ZQ-MARKER-COMPANY-7',
                'WRONG_ORG_MARKER',
                'EXTRA_ROLE_MARKER',
                'EXTRA_ORG_MARKER',
                '2019',
                '2020',
                '2021',
                '2022',
                'EVIDENCE_MARKER',
                'EXPERIENCE_EVIDENCE_MARKER',
                'SYNTHETIC_ISSUE',
                'zq-marker-document-7.result.json',
                'zq-marker-document-7',
                str(self.source),
                str(self.output),
            }.issubset(markers)
        )
        for flag in ([], ['--json']):
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = main(
                    ['compare', '--results', str(self.source), '--gold', str(self.output), *flag]
                )
            self.assertEqual(code, 0)
            text = out.getvalue() + err.getvalue()
            for marker in markers:
                self.assertNotIn(marker, text)
            self.assertTrue(text)
            if flag:
                self.assertEqual(json.loads(out.getvalue()), compare(self.source, self.output))

    def test_duplicates_and_unknown_components(self) -> None:
        """Count duplicate predictions once per available gold occurrence."""
        self.output.mkdir()
        item = experience('ROLE_MARKER', 'ORG_MARKER', '2020', None, False)
        reference = gold_record()
        reference['experiences'] = [item]
        save(self.output / 'a.gold.json', reference)
        save(self.source / 'a.result.json', result_record({}, [item, item]))
        metrics = compare(self.source, self.output)
        self.assertEqual(metrics['experiences']['positions']['matched'], 1)
        self.assertEqual(metrics['experiences']['positions']['precision'], 0.5)
        self.assertEqual(metrics['experiences']['companies']['predicted'], 1)
        reference['experiences'] = [dict(item, company=None, start=None)]
        save(self.output / 'a.gold.json', reference)
        save(self.source / 'a.result.json', result_record({}, reference['experiences']))
        metrics = compare(self.source, self.output)
        for counts in metrics['experiences'].values():
            self.assertEqual(counts['matched'], 0)

    def test_entirely_unknown_experiences_are_excluded(self) -> None:
        """Exclude wholly unknown records from exact and component denominators."""
        self.output.mkdir()
        unknown = dict(title=None, company=None, start=None, end=None, ongoing=False)
        reference = gold_record()
        reference['experiences'] = [unknown]
        save(self.output / 'unknown.gold.json', reference)
        save(self.source / 'unknown.result.json', result_record({}, [unknown, unknown]))
        metrics = compare(self.source, self.output)
        self.assertEqual(metrics['compared'], 1)
        for kind, counts in metrics['experiences'].items():
            self.assertEqual(
                counts,
                {
                    'matched': 0,
                    'predicted': 0,
                    'gold': 0,
                    'precision': 0.0,
                    'recall': 0.0,
                    'loose': {
                        'matched': 0,
                        'predicted': 0,
                        'gold': 0,
                        'precision': 0.0,
                        'recall': 0.0,
                    },
                    **{
                        level: {
                            'matched': 0,
                            'predicted': 0,
                            'gold': 0,
                            'precision': 0.0,
                            'recall': 0.0,
                        }
                        for level in ('core', 'coreYear')
                        if (level == 'core' and kind != 'companyPeriods')
                        or (level == 'coreYear' and kind == 'positions')
                    },
                },
            )

    def test_invalid_and_absent_pairs_are_counted(self) -> None:
        """Skip invalid records without exposing their contents or guessing matches."""
        self.output.mkdir()
        save(self.output / 'missing.gold.json', gold_record())
        (self.output / 'invalid.gold.json').write_bytes(b'PRIVATE_BAD_GOLD')
        save(self.output / 'bad-result.gold.json', gold_record())
        save(self.source / 'bad-result.result.json', {'seconds': 'PRIVATE_BAD_SECONDS'})
        save(self.source / 'unpaired.result.json', result_record({}, []))
        save(self.output / 'draft.gold.json', dict(gold_record(), status='DRAFT'))
        save(self.source / 'draft.result.json', result_record({}, []))
        save(self.source / 'invalid.result.json', result_record({}, []))
        metrics = compare(self.source, self.output)
        self.assertEqual(metrics['compared'], 0)
        self.assertEqual(metrics['missing_results'], 1)
        self.assertEqual(metrics['invalid_gold'], 1)
        self.assertEqual(metrics['invalid_results'], 1)
        self.assertEqual(metrics['unpaired_results'], 1)
        self.assertEqual(metrics['skipped_draft'], 1)
        self.assertEqual(metrics['average_seconds'], 0)
        self.assertEqual(metrics['experiences']['positions']['precision'], 0)

    def test_cli_missing_directories_are_private(self) -> None:
        """Return only a fixed code when either comparison directory is absent."""
        absent = self.root / 'zq-private-absent-directory-7'
        for results, gold in ((absent, self.source), (self.source, absent)):
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = main(['compare', '--results', str(results), '--gold', str(gold)])
            self.assertEqual(code, 2)
            self.assertEqual(out.getvalue(), 'INVALID_INPUT_DIRECTORY\n')
            self.assertEqual(err.getvalue(), '')
