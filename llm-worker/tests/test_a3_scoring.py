"""Strict regression and loose scoring acceptance with synthetic data only."""

import io
import json
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

import test_batch_gold_compare as fixtures
import test_bench as bench_fixtures
import test_gold_v2 as grouped

from cv_compass_llm.bench import print_bench
from cv_compass_llm.cli import main
from cv_compass_llm.compare import compare
from cv_compass_llm.gold import normalize, normalize_loose
from cv_compass_llm.providers import FakeProvider


class NormalizeLooseTests(unittest.TestCase):
    def test_unicode_case_and_whitespace(self):
        self.assertEqual(normalize_loose('  CAFÉ\tSTRASSE\n'), 'café strasse')
        self.assertEqual(normalize_loose('cafe\u0301 Straße'), 'café strasse')

    def test_all_approved_typographic_variants(self):
        for variants, expected in (
            ('‐‑‒–—−-', '-'),
            ('‘’‚‛′', "'"),
            ('“”„″', '"'),
        ):
            for character in variants:
                with self.subTest(character=character):
                    self.assertEqual(normalize_loose('A' + character + 'B'), 'a' + expected + 'b')
        self.assertEqual(normalize_loose(' : . A \t– B | C / D ; . : '), 'a-b|c/d')

    def test_empty_values_and_repeated_boundary_stripping(self):
        for value in (None, '', '\t\n', ' . , ; : \t. : '):
            with self.subTest(value=value):
                self.assertIsNone(normalize_loose(value))
        self.assertEqual(normalize_loose('. ; : Alpha , . ;'), 'alpha')
        self.assertEqual(normalize(' . ; '), '. ;')
        self.assertEqual(normalize(''), '')

    def test_words_and_internal_punctuation_are_preserved(self):
        self.assertEqual(normalize_loose('Acme Co, Hanoi'), 'acme co, hanoi')
        self.assertNotEqual(normalize_loose('Acme Co'), normalize_loose('Acme Co, Hanoi'))
        for value in ('A, B', 'A; B', 'A: B', '(A)', 'A!'):
            with self.subTest(value=value):
                self.assertEqual(normalize_loose(value), value.casefold())


class CompareLooseTests(fixtures.DirectoryTests):
    def pair(self, reference, personal, experiences):
        self.output.mkdir(exist_ok=True)
        fixtures.save(self.output / 'synthetic.gold.json', reference)
        fixtures.save(
            self.source / 'synthetic.result.json', fixtures.result_record(personal, experiences)
        )
        return compare(self.source, self.output)

    def test_existing_fixture_strict_summary_is_unchanged(self):
        fixtures.CompareTests.fixtures(self)
        metrics = compare(self.source, self.output)
        strict = dict(metrics)
        for section in ('personal', 'experiences'):
            strict[section] = {
                key: {metric: value for metric, value in counts.items() if metric != 'loose'}
                for key, counts in metrics[section].items()
            }
        self.assertEqual(
            strict,
            {
                'compared': 2,
                'skipped_draft': 1,
                'missing_results': 0,
                'unpaired_results': 0,
                'invalid_gold': 0,
                'invalid_results': 0,
                'personal': {
                    'fullName': {'correct': 1, 'wrong': 0, 'missing': 1, 'extra': 0},
                    'gender': {'correct': 1, 'wrong': 1, 'missing': 0, 'extra': 0},
                    'location': {'correct': 1, 'wrong': 0, 'missing': 1, 'extra': 0},
                    'dateOfBirth': {'correct': 0, 'wrong': 1, 'missing': 0, 'extra': 1},
                },
                'experiences': {
                    kind: {
                        'matched': 1,
                        'predicted': 3,
                        'gold': 3,
                        'precision': 1 / 3,
                        'recall': 1 / 3,
                    }
                    for kind in ('companies', 'companyPeriods', 'titles', 'positions')
                },
                'average_seconds': 3.0,
                'companyLocation': 'NOT_SCORED',
            },
        )

    def test_typographic_matches_only_at_loose_level(self):
        for expected, actual in (
            ('Acme-Co', 'Acme–Co'),
            ("Acme'Co", 'Acme‘Co'),
            ('Acme Co', 'Acme Co.'),
            ('Acme|Co/Unit', 'Acme | Co / Unit'),
        ):
            for field in ('company', 'title'):
                with self.subTest(expected=expected, field=field):
                    item = fixtures.experience('Role', 'Company', '2020', '2021', False)
                    item[field] = expected
                    reference = dict(fixtures.gold_record(), experiences=[item])
                    metrics = self.pair(reference, {}, [dict(item, **{field: actual})])
                    for kind, counts in metrics['experiences'].items():
                        strict_match = int(
                            field == 'title' and kind in ('companies', 'companyPeriods')
                        )
                        self.assertEqual(counts['matched'], strict_match)
                        self.assertEqual(counts['predicted'], 1)
                        self.assertEqual(counts['gold'], 1)
                        self.assertEqual(
                            counts['loose'],
                            {'matched': 1, 'predicted': 1, 'gold': 1, 'precision': 1, 'recall': 1},
                        )

    def test_longer_company_or_title_is_wrong_at_both_levels(self):
        for field in ('company', 'title'):
            with self.subTest(field=field):
                item = fixtures.experience('Acme Co', 'Acme Co', '2020', '2021', False)
                metrics = self.pair(
                    dict(fixtures.gold_record(), experiences=[item]),
                    {},
                    [dict(item, **{field: 'Acme Co, Hanoi'})],
                )
                counts = metrics['experiences']['positions']
                for level in (counts, counts['loose']):
                    self.assertEqual(level['matched'], 0)
                    self.assertEqual(level['predicted'], 1)
                    self.assertEqual(level['gold'], 1)
                    self.assertEqual(level['precision'], 0)
                    self.assertEqual(level['recall'], 0)

    def test_personal_loose_categories(self):
        metrics = self.pair(
            fixtures.gold_record(fullName='Synthetic Name', gender='FEMALE', dateOfBirth='2000'),
            {'fullName': ' : SYNTHETIC NAME. ', 'gender': 'OTHER', 'location': 'Synthetic City'},
            [],
        )
        for field, category in (
            ('fullName', 'correct'),
            ('gender', 'wrong'),
            ('location', 'extra'),
            ('dateOfBirth', 'missing'),
        ):
            self.assertEqual(
                metrics['personal'][field]['loose'],
                {key: int(key == category) for key in ('correct', 'wrong', 'missing', 'extra')},
            )
        self.assertEqual(metrics['personal']['fullName']['wrong'], 1)

    def test_personal_empty_loose_values_and_longer_names(self):
        for expected, actual, category in (
            (None, None, 'correct'),
            (' . ; ', None, 'correct'),
            (None, ' : . ', 'correct'),
            (' . ', 'Synthetic Name', 'extra'),
            ('Synthetic Name', ' . ', 'missing'),
            ('Acme Co', 'Acme Co, Hanoi', 'wrong'),
        ):
            with self.subTest(expected=expected, actual=actual):
                metrics = self.pair(
                    fixtures.gold_record(fullName=expected), {'fullName': actual}, []
                )
                self.assertEqual(
                    metrics['personal']['fullName']['loose'],
                    {key: int(key == category) for key in ('correct', 'wrong', 'missing', 'extra')},
                )
                if category == 'wrong':
                    self.assertEqual(metrics['personal']['fullName']['wrong'], 1)

    def test_loose_preserves_grouping_periods_and_duplicate_limits(self):
        reference = grouped.record(
            [
                grouped.company(
                    'Acme-Co',
                    [
                        grouped.position('Role', '2020', '2021'),
                        grouped.position('Role', '2022', '2023'),
                    ],
                )
            ]
        )
        rows = [
            fixtures.experience('Role', 'Acme-Co', '2020', '2021', False),
            fixtures.experience('Role', 'Acme–Co', '2022', '2023', False),
        ]
        metrics = self.pair(reference, {}, rows)['experiences']
        self.assertEqual(
            metrics['companies']['loose'],
            {'matched': 1, 'predicted': 2, 'gold': 1, 'precision': 0.5, 'recall': 1},
        )
        self.assertEqual(metrics['companyPeriods']['loose']['matched'], 0)
        self.assertEqual(metrics['positions']['loose']['matched'], 2)
        metrics = self.pair(reference, {}, rows + [rows[1]])['experiences']
        self.assertEqual(metrics['positions']['loose']['matched'], 2)
        self.assertEqual(metrics['positions']['loose']['predicted'], 3)


class BenchLooseTests(unittest.TestCase):
    def setUp(self):
        bench_fixtures.BenchTests.setUp(self)

    def test_bench_pools_loose_counts_prints_adjacent_columns_and_emits_json(self):
        self.configs = self.configs[:1]
        item = fixtures.experience('Role', 'Acme-Co', '2020', '2021', False)
        path = self.gold / (self.filename + '.gold.json')
        fixtures.save(path, dict(fixtures.gold_record(), experiences=[item]))
        records = ([dict(item, company='Acme–Co')], [item, dict(item, company='Other')])

        def batch(source, output, provider, **options):
            output.mkdir(parents=True)
            iteration = int(output.name[1:]) - 1
            fixtures.save(
                output / (self.filename + '.result.json'),
                fixtures.result_record({}, records[iteration]),
            )
            return {'files': 1, 'ok': 1, 'llm_calls': 0, 'issues': {}, 'errors': {}}

        with patch('cv_compass_llm.cli.run_batch', side_effect=batch):
            report = bench_fixtures.BenchTests.run_bench(
                self, lambda config: FakeProvider([]), repeat=2
            )
        metrics = report['configurations'][0]['metrics']
        for kind in ('companies', 'companyPeriods', 'titles', 'positions'):
            prefix = f'experiences.{kind}.'
            self.assertEqual(metrics[prefix + 'precision'], 1 / 3)
            self.assertEqual(metrics[prefix + 'recall'], 0.5)
            self.assertEqual(metrics[prefix + 'loose.matched'], 2)
            self.assertEqual(metrics[prefix + 'loose.predicted'], 3)
            self.assertEqual(metrics[prefix + 'loose.gold'], 2)
            self.assertEqual(metrics[prefix + 'loose.precision'], 2 / 3)
            self.assertEqual(metrics[prefix + 'loose.recall'], 1)
        self.assertEqual(metrics['personal.fullName.loose.correct'], 2)
        output = io.StringIO()
        with redirect_stdout(output):
            print_bench(report)
        lines = output.getvalue().splitlines()
        self.assertEqual(lines[0], 'metric\tbase\tΔ\tbase loose\tΔ')
        cells = {line.split('\t')[0]: line.split('\t')[1:] for line in lines[1:]}
        self.assertEqual(
            cells['experiences.positions.precision'], ['0.33333333', '0', '0.66666667', '0']
        )
        self.assertEqual(cells['experiences.positions.recall'], ['0.5', '0', '1', '0'])
        output = io.StringIO()
        with patch('cv_compass_llm.cli.run_bench', return_value=report), redirect_stdout(output):
            code = main(
                [
                    'bench',
                    '--input',
                    'synthetic',
                    '--gold',
                    'synthetic',
                    '--output',
                    'synthetic',
                    '--config',
                    'base=synthetic.env',
                    '--json',
                ]
            )
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(output.getvalue()), report)


if __name__ == '__main__':
    unittest.main()
