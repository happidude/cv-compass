"""Synthetic acceptance cases for grouped gold and company-scoped metrics."""

import copy
import io
import json
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

from test_batch_gold_compare import (
    DirectoryTests,
    experience,
    gold_record,
    read,
    result_record,
    save,
)

from cv_compass_llm.cli import main, run_batch
from cv_compass_llm.compare import compare, normalize
from cv_compass_llm.gold import (
    SCHEMA_VERSION,
    company_period,
    draft_gold,
    effective_period,
    group_experiences,
    load_gold,
)
from cv_compass_llm.providers import FakeProvider
from cv_compass_llm.schema import ExtractionResult, Issue


def position(title='MANAGER', start='2020', end='2024', ongoing=False):
    return dict(title=title, start=start, end=end, ongoing=ongoing)


def company(name='Acme Synthetic', positions=None, *, start=None, end=None, ongoing=False):
    return {
        'company': name, 'companyLocation': None, 'start': start, 'end': end,
        'ongoing': ongoing, 'positions': [position()] if positions is None else positions,
    }


def record(companies=None):
    return dict(gold_record(), schemaVersion=SCHEMA_VERSION,
                experiences=[company()] if companies is None else companies)


class GoldV2Tests(DirectoryTests):
    def test_valid_nullable_values_and_birth_precision(self):
        path = self.source / 'synthetic.gold.json'
        for birth in (None, '2000', '2000-02', '2000-02-29'):
            for status in ('DRAFT', 'CONFIRMED'):
                data = record([
                    company(positions=[position(None)]),
                    company(None, [position()]),
                ])
                data['status'] = status
                data['personal']['dateOfBirth'] = birth
                data['experiences'][0]['companyLocation'] = 'Synthetic City'
                save(path, data)
                self.assertEqual(load_gold(path), data)
        save(path, record([]))
        self.assertEqual(load_gold(path), record([]))

    def test_strict_v2_rejects_each_invalid_structure(self):
        path = self.source / 'synthetic.gold.json'
        base = record()
        mutations = [
            ((), 'schemaVersion', None), ((), 'schemaVersion', 'experience-gold/3'),
            ((), 'status', 'confirmed'), ((), 'extra', None),
            ((), 'personal', []), ((), 'experiences', {}),
            (('personal',), 'fullName', 1), (('personal',), 'extra', None),
            (('experiences', 0), 'company', False),
            (('experiences', 0), 'companyLocation', []),
            (('experiences', 0), 'positions', []),
            (('experiences', 0), 'positions', None),
            (('experiences', 0), 'positions', [None]),
            (('experiences', 0), 'extra', None),
            (('experiences', 0, 'positions', 0), 'title', 1),
            (('experiences', 0, 'positions', 0), 'extra', None),
        ]
        for location in (('experiences', 0), ('experiences', 0, 'positions', 0)):
            mutations.extend((location, key, value) for key, value in (
                ('start', 2020), ('end', False), ('ongoing', 1), ('ongoing', None),
                ('start', '2020-00'), ('start', '2020-13'), ('start', '2020-1'),
                ('end', '2020-01-01'), ('start', '20'), ('start', '2020\n'),
                ('ongoing', True),  # Both levels have a non-null end in this fixture.
            ))
        base['experiences'][0]['end'] = '2024'
        for location, key, value in mutations:
            with self.subTest(location=location, key=key, value=value):
                data = copy.deepcopy(base)
                target = data
                for part in location:
                    target = target[part]
                target[key] = value
                save(path, data)
                with self.assertRaisesRegex(ValueError, '^INVALID_GOLD$'):
                    load_gold(path)
        for location in ((), ('personal',), ('experiences', 0),
                         ('experiences', 0, 'positions', 0)):
            target = base
            for part in location:
                target = target[part]
            for key in target:
                # Removing the version alone makes an invalid flat v1 structure.
                data = copy.deepcopy(base)
                nested = data
                for part in location:
                    nested = nested[part]
                del nested[key]
                save(path, data)
                with self.subTest(location=location, missing=key):
                    with self.assertRaisesRegex(ValueError, '^INVALID_GOLD$'):
                        load_gold(path)
        for birth in ('2001-02-29', '2000-02-30', '2000-13-01', '2000-01-00',
                      '0000-01-01', '01/02/2000', '2000-1-01', '2000-01-01T00:00:00'):
            data = record()
            data['personal']['dateOfBirth'] = birth
            save(path, data)
            with self.subTest(birth=birth):
                with self.assertRaisesRegex(ValueError, '^INVALID_GOLD$'):
                    load_gold(path)
        save(path, record([company(None, [position(None)])]))
        with self.assertRaisesRegex(ValueError, '^INVALID_GOLD$'):
            load_gold(path)

    def test_v1_and_grouping_preserve_order_and_only_merge_adjacent_known_companies(self):
        rows = [experience('A', name, '2020', None, True) for name in (
            'Acme Sýnthetic', '  ACME SÝNTHETIC\t', 'Other Synthetic',
            'Acme Sýnthetic', None, None,
        )]
        before = copy.deepcopy(rows)
        grouped = group_experiences(rows)
        self.assertEqual([len(item['positions']) for item in grouped], [2, 1, 1, 1, 1])
        self.assertEqual([item['company'] for item in grouped], [
            'Acme Sýnthetic', 'Other Synthetic', 'Acme Sýnthetic', None, None,
        ])
        self.assertEqual(normalize(rows[0]['company']), normalize(rows[1]['company']))
        self.assertEqual(rows, before)
        path = self.source / 'legacy.gold.json'
        legacy = dict(gold_record(), experiences=rows)
        save(path, legacy)
        self.assertEqual(load_gold(path), dict(legacy, schemaVersion=SCHEMA_VERSION,
                                               experiences=grouped))

    def test_company_period_and_effective_period_are_pure(self):
        rows = [position('A', '2018-06', '2020-02'), position('B', '2018', '2020'),
                position('C', '2019', '2020-11')]
        item = company(positions=rows)
        original = copy.deepcopy(item)
        self.assertEqual(company_period(item), ('2018', '2020', False))
        self.assertEqual(company_period(company(positions=[
            position('A', '2018', '2020'), position('B', '2017-12', '2021-01'),
        ])), ('2017-12', '2021-01', False))
        period = ('2010', '2024', False)
        for row in (position(start=None), position(end=None),
                    position(start=None, end=None, ongoing=True)):
            self.assertEqual(effective_period(row, period), period)
        complete = position(start='2020', end=None, ongoing=True)
        self.assertEqual(effective_period(complete, period), ('2020', None, True))
        self.assertEqual(effective_period(rows[0], period), ('2018-06', '2020-02', False))
        self.assertEqual(company_period(company(positions=[complete, *rows])),
                         ('2018', None, True))
        self.assertEqual(company_period(company(positions=[position(start=None, end=None)])),
                         (None, None, False))
        for explicit in ({'start': '2000'}, {'end': '2025'}, {'ongoing': True}):
            explicit_company = company(positions=rows, **explicit)
            self.assertEqual(company_period(explicit_company),
                             (explicit_company['start'], explicit_company['end'],
                              explicit_company['ongoing']))
        self.assertEqual(item, original)

    def test_explicit_partial_company_periods_and_inferred_ongoing(self):
        rows = [position('A', '2010', '2020'),
                position('B', '2018-06', None, True)]
        for explicit in ({'start': '2015'}, {'end': '2024'}, {'ongoing': True}):
            item = company(positions=rows, **explicit)
            self.assertEqual(company_period(item), (
                explicit.get('start'), explicit.get('end'), explicit.get('ongoing', False),
            ))
        item = company(positions=rows)
        self.assertEqual(company_period(item), ('2010', None, True))
        self.assertEqual(company_period(company(positions=[position(None, None, None)])),
                         (None, None, False))
        data = record([item, company(ongoing=True)])
        path = self.source / 'ongoing.gold.json'
        save(path, data)
        self.assertEqual(load_gold(path), data)

    def test_draft_v2_groups_positions_and_keeps_company_period_unset(self):
        rows = [experience('A', 'Acme Synthetic', '2018', '2020', False),
                experience('B', ' ACME SYNTHETIC ', '2020', None, True),
                experience('C', 'Other Synthetic', '2010', '2012', False),
                experience('D', 'Acme Synthetic', '2012', '2018', False)]
        original = result_record({}, rows)
        path = self.source / 'synthetic.result.json'
        save(path, original)
        source_bytes = path.read_bytes()
        self.assertEqual(draft_gold(self.source, self.output, repo_root=self.repo), {
            'created': 1, 'skipped_existing': 0, 'errors': 0,
        })
        destination = self.output / 'synthetic.gold.json'
        data = load_gold(destination)
        self.assertEqual(data['schemaVersion'], SCHEMA_VERSION)
        self.assertEqual(data['status'], 'DRAFT')
        self.assertEqual([len(item['positions']) for item in data['experiences']], [2, 1, 1])
        self.assertEqual(data['experiences'][0]['positions'], [
            position('A', '2018', '2020'), position('B', '2020', None, True),
        ])
        for item in data['experiences']:
            self.assertIsNone(item['companyLocation'])
            self.assertEqual((item['start'], item['end'], item['ongoing']), (None, None, False))
        before = destination.read_bytes()
        self.assertEqual(path.read_bytes(), source_bytes)
        save(path, result_record({}, []))
        self.assertEqual(draft_gold(self.source, self.output, repo_root=self.repo), {
            'created': 0, 'skipped_existing': 1, 'errors': 0,
        })
        self.assertEqual(destination.read_bytes(), before)

    def test_draft_v2_groups_and_leaves_company_period_for_review(self):
        rows = [experience('A', 'Acme Synthetic', '2018', '2020', False),
                experience('B', ' ACME SYNTHETIC ', '2020', None, True)]
        save(self.source / 'synthetic.result.json', result_record({}, rows))
        self.assertEqual(draft_gold(self.source, self.output, repo_root=self.repo),
                         {'created': 1, 'skipped_existing': 0, 'errors': 0})
        path = self.output / 'synthetic.gold.json'
        draft = load_gold(path)
        self.assertEqual(draft['schemaVersion'], SCHEMA_VERSION)
        self.assertEqual(draft['status'], 'DRAFT')
        self.assertEqual(draft['experiences'], [company(positions=[
            position('A', '2018', '2020'), position('B', '2020', None, True),
        ])])
        original = path.read_bytes()
        self.assertEqual(draft_gold(self.source, self.output, repo_root=self.repo),
                         {'created': 0, 'skipped_existing': 1, 'errors': 0})
        self.assertEqual(path.read_bytes(), original)


class GroupedCompareTests(DirectoryTests):
    def test_hand_calculated_company_and_position_metrics(self):
        self.output.mkdir()
        companies = [
            company(positions=[position(), position('EXECUTIVE', '2018', '2020')]),
            company('Repeat Synthetic', [position('R', '2010', '2011')]),
            company('Other Synthetic', [position('O', '2012', '2013')]),
            company('Repeat Synthetic', [position('R', '2014', '2015')]),
            company('Shift Synthetic', [position('A', None, '2020'),
                                        position('B', None, None)], start='2019', end='2022'),
        ]
        rows = [experience(pos['title'], item['company'], pos['start'], pos['end'],
                           pos['ongoing']) for item in companies[:4]
                for pos in item['positions']]
        rows.extend([experience('A', 'Shift Synthetic', '2019', '2020', False),
                     experience('B', 'Shift Synthetic', '2020', '2022', False)])
        save(self.output / 'PRIVATE_FILE_MARKER.gold.json', record(companies))
        save(self.source / 'PRIVATE_FILE_MARKER.result.json', result_record({}, rows))
        metrics = compare(self.source, self.output)
        self.assertEqual(metrics['compared'], 1)
        self.assertEqual(metrics['companyLocation'], 'NOT_SCORED')
        for kind, matched, count in (('companies', 5, 5), ('companyPeriods', 5, 5),
                                     ('titles', 7, 7), ('positions', 5, 7)):
            self.assertEqual(metrics['experiences'][kind], {
                'matched': matched, 'predicted': count, 'gold': count,
                'precision': matched / count, 'recall': matched / count,
            })
        for flag in ([], ['--json']):
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                self.assertEqual(main(['compare', '--results', str(self.source),
                                       '--gold', str(self.output), *flag]), 0)
            for marker in ('Acme Synthetic', 'Repeat Synthetic', 'Other Synthetic',
                           'Shift Synthetic', 'MANAGER', 'EXECUTIVE', '2018', '2024',
                           'PRIVATE_FILE_MARKER', str(self.source), str(self.output),
                           'EVIDENCE_MARKER'):
                self.assertNotIn(marker, out.getvalue() + err.getvalue())
            if flag:
                self.assertEqual(json.loads(out.getvalue()), metrics)

    def test_v1_and_v2_share_metrics_and_result_period_is_always_derived(self):
        self.output.mkdir()
        rows = [experience('A', 'Acme Synthetic', '2018', '2020', False),
                experience('B', 'Acme Synthetic', '2020', '2024', False)]
        path = self.output / 'synthetic.gold.json'
        save(self.source / 'synthetic.result.json', result_record({}, rows))
        save(path, dict(gold_record(), experiences=rows))
        legacy = compare(self.source, self.output)
        save(path, record(group_experiences(rows)))
        self.assertEqual(compare(self.source, self.output), legacy)
        for counts in legacy['experiences'].values():
            self.assertEqual(counts['precision'], 1)
            self.assertEqual(counts['recall'], 1)
        predicted = read(self.source / 'synthetic.result.json')
        predicted['experiences'][0]['companyPeriod'] = 'IGNORED_MARKER'
        save(self.source / 'synthetic.result.json', predicted)
        self.assertEqual(compare(self.source, self.output), legacy)

    def test_multisets_across_nonadjacent_companies_and_null_main_components(self):
        self.output.mkdir()
        a = experience('A', 'Acme Synthetic', '2020', '2021', False)
        other = experience('B', 'Other Synthetic', '2022', '2023', False)
        rows = [a, other, a, dict(a, company=None), dict(a, title=None)]
        save(self.output / 'synthetic.gold.json', record(group_experiences(rows)))
        save(self.source / 'synthetic.result.json', result_record({}, rows + [other, a]))
        metrics = compare(self.source, self.output)['experiences']
        for kind, matched, count in (('companies', 4, 6), ('companyPeriods', 4, 6),
                                     ('titles', 3, 5), ('positions', 3, 5)):
            self.assertEqual(metrics[kind], {
                'matched': matched, 'predicted': count, 'gold': matched,
                'precision': matched / count, 'recall': 1,
            })


class IssueFieldTests(DirectoryTests):
    def test_batch_retains_code_totals_and_counts_each_field_across_files(self):
        for name in ('a.json', 'b.json'):
            save(self.source / name, {'pages': [
                {'page_number': 1, 'status': 'ok', 'raw_text': 'Synthetic text'},
            ]})
        issues = (Issue('QUOTE_NOT_FOUND', 'title'), Issue('QUOTE_NOT_FOUND', 'title'),
                  Issue('QUOTE_NOT_FOUND', 'company'), Issue('INVALID_DATE', 'start'),
                  Issue('BLOCK_LIMIT_REACHED'))
        result = ExtractionResult((), (), issues, 'synthetic', 'fake')
        with patch('cv_compass_llm.cli.extract', return_value=result):
            summary = run_batch(self.source, self.output, FakeProvider([]), repo_root=self.repo)
        self.assertEqual(summary['issues'], {
            'QUOTE_NOT_FOUND': 6, 'INVALID_DATE': 2, 'BLOCK_LIMIT_REACHED': 2,
        })
        self.assertEqual(summary['issues_by_field'], {
            'QUOTE_NOT_FOUND.title': 4, 'QUOTE_NOT_FOUND.company': 2,
            'INVALID_DATE.start': 2, 'BLOCK_LIMIT_REACHED': 2,
        })
        self.assertEqual(read(self.output / 'run-summary.json'), summary)
