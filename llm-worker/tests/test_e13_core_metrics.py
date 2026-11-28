"""Core scoring acceptance against an independent, synthetic reference scorer."""

import io
import json
import re
import unittest
from collections import Counter
from contextlib import redirect_stdout

import test_batch_gold_compare as fixtures
import test_gold_v2 as grouped

from cv_compass_llm.bench import _aggregate, print_bench
from cv_compass_llm.compare import (
    compare,
    core_company,
    core_experience_keys,
    core_name,
    core_title,
)
from cv_compass_llm.gold import company_period, effective_period, group_experiences, normalize_loose


def reference_core(value, kind):
    """Reimplement the approved rules locally, without an external scorer file."""
    if not value:
        return None
    cut = re.compile(r'\s[-–—]\s|\s*\|\s*|\(')
    legal = re.compile(
        r'\b(công ty|cty|cổ phần|tnhh|co|ltd|jsc|corp|corporation|company|companny|group|vietnam|việt nam|viet nam|vn)\b'
    )
    if kind in ('company', 'name'):
        value = re.sub(r'\(.*?\)', ' ', value)
    if kind == 'name':
        value = re.sub(r'(?i)\b(mr|mrs|ms|miss)\b\.?', ' ', value)
    else:
        value = cut.split(value)[0]
    value = normalize_loose(value) or ''
    if kind == 'company':
        value = legal.sub(' ', value)
    return re.sub(r'[^0-9a-zà-ỹđ]+', '', value) or None


def reference_keys(items, kind):
    result = Counter()
    for item in items:
        company = reference_core(item['company'], 'company')
        if company is None:
            continue
        if kind == 'companies':
            result[company] += 1
        else:
            for position in item['positions']:
                title = reference_core(position['title'], 'title')
                if title is None:
                    continue
                key = (company, title)
                period = effective_period(position, company_period(item))
                if kind == 'positions':
                    key += period
                elif kind == 'positionsYear':
                    key += tuple(date[:4] if date else None for date in period[:2])
                result[key] += 1
    return result


def reference_counts(gold, predicted, kind):
    expected, actual = reference_keys(gold, kind), reference_keys(predicted, kind)
    matched = sum((expected & actual).values())
    return {
        'matched': matched,
        'predicted': actual.total(),
        'gold': expected.total(),
        'precision': matched / actual.total() if actual else 0.0,
        'recall': matched / expected.total() if expected else 0.0,
    }


class CoreRuleTests(unittest.TestCase):
    def test_exact_rules_and_cut_order(self):
        cases = {
            'company': [
                (None, None),
                ('', None),
                ('Công ty cổ phần Vân Tinh (Lab) Việt Nam – miền bắc', 'vântinh'),
                ('Vân Tinh Co., Ltd', 'vântinh'),
                ('Vân Tinh JSC|Unit', 'vântinh'),
                ('Vân Tinh (Lab) Corp', 'vântinh'),
                ('cty TNHH Vietnam Việt Nam Viet Nam VN', None),
                ('Coherent Corporation Company Companny Group', 'coherent'),
                ('A-B Ltd', 'ab'),
                ('A ‐ B Ltd', 'ab'),
                ('A — B Ltd', 'a'),
                ('Đỏ JSC', 'đỏ'),
            ],
            'title': [
                (None, None),
                (' . ', None),
                ('Team Lead - Platform', 'teamlead'),
                ('Team Lead(Product)', 'teamlead'),
                ('Team Lead | Unit', 'teamlead'),
                ('Team-Lead', 'teamlead'),
                ('(Alias) Lead', None),
            ],
            'name': [
                (None, None),
                ('Mr. Tên Mẫu (Alias)', 'tênmẫu'),
                ('MRS. Tên Mẫu (Alias)', 'tênmẫu'),
                ('Ms Tên Mẫu', 'tênmẫu'),
                ('Miss. Tên Mẫu', 'tênmẫu'),
                ('Mr. (Alias)', None),
                ('Mrinal Mẫu', 'mrinalmẫu'),
            ],
        }
        for kind, normalizer in (
            ('company', core_company),
            ('title', core_title),
            ('name', core_name),
        ):
            for value, expected in cases[kind]:
                with self.subTest(kind=kind, value=value):
                    self.assertEqual(reference_core(value, kind), expected)
                    self.assertEqual(normalizer(value), expected)

    def test_effective_period_months_ongoing_and_years(self):
        items = [
            grouped.company(
                'Vân Tinh Ltd',
                [grouped.position('Lead (Platform)', None, None)],
                start='2020-03',
                end='2022-06',
            ),
            grouped.company(
                'Mây Đỏ JSC',
                [grouped.position('Lead - Platform', '2023-02', None, True)],
            ),
            grouped.company('TNHH', [grouped.position('Lead')]),
            grouped.company('Lam Vũ', [grouped.position(None)]),
        ]
        for kind in ('companies', 'titles', 'positions', 'positionsYear'):
            self.assertEqual(core_experience_keys(items, kind), reference_keys(items, kind))
        self.assertEqual(
            core_experience_keys(items, 'positions'),
            Counter(
                {
                    ('vântinh', 'lead', '2020-03', '2022-06', False): 1,
                    ('mâyđỏ', 'lead', '2023-02', None, True): 1,
                }
            ),
        )
        unknown_end = grouped.company('Mây Đỏ', [grouped.position('Lead', '2023-02', None, False)])
        self.assertNotEqual(
            core_experience_keys([items[1]], 'positions'),
            core_experience_keys([unknown_end], 'positions'),
        )
        self.assertEqual(
            core_experience_keys([items[1]], 'positionsYear'),
            core_experience_keys([unknown_end], 'positionsYear'),
        )


class CoreCompareTests(fixtures.DirectoryTests):
    def pair(self, gold, rows, name=None):
        self.output.mkdir(exist_ok=True)
        fixtures.save(self.output / 'synthetic.gold.json', gold)
        fixtures.save(
            self.source / 'synthetic.result.json',
            fixtures.result_record({'fullName': name}, rows),
        )
        return compare(self.source, self.output)

    def test_compare_and_bench_pool_reference_counts_without_leaking_values(self):
        gold = grouped.record(
            [
                grouped.company(
                    'Công ty Vân Tinh (Lab) Việt Nam – Unit',
                    [
                        grouped.position('Team Lead - Platform', '2020-03', '2022-06'),
                        grouped.position('Manager (Regional)', '2022-07', None, True),
                    ],
                ),
                grouped.company(
                    'Mây Đỏ JSC|Branch', [grouped.position('Engineer', '2018', '2019')]
                ),
                grouped.company('Lam Vũ (Lab) Co., Ltd', [grouped.position('Director')]),
                grouped.company('TNHH Việt Nam', [grouped.position('Ignored')]),
            ]
        )
        gold['personal']['fullName'] = 'Mr. Tên Mẫu (Alias)'
        rows = [
            fixtures.experience(
                'Team Lead (Platform)', 'Vân Tinh Co., Ltd', '2020-04', '2022-06', False
            ),
            fixtures.experience('Manager - Regional', 'Vân Tinh Co., Ltd', '2022-07', None, True),
            fixtures.experience('Engineer', 'Mây Đỏ Việt Nam', '2018', '2019', False),
            fixtures.experience('Director', 'Lam Vũ JSC', '2020', '2024', False),
            fixtures.experience('Ignored', 'Công ty TNHH Việt Nam', '2020', '2024', False),
        ]
        first = self.pair(gold, rows, 'Tên Mẫu')
        second = self.pair(gold, rows + [rows[0]], 'Mrs. Tên Mẫu (Other Alias)')
        for comparison, predicted in ((first, rows), (second, rows + [rows[0]])):
            self.assertEqual(comparison['compared'], 1)
            self.assertEqual(comparison['invalid_gold'], 0)
            self.assertEqual(comparison['invalid_results'], 0)
            self.assertEqual(
                comparison['personal']['fullName']['core'],
                {'correct': 1, 'wrong': 0, 'missing': 0, 'extra': 0},
            )
            for kind, metric, reference_kind in (
                ('companies', 'core', 'companies'),
                ('titles', 'core', 'titles'),
                ('positions', 'core', 'positions'),
                ('positions', 'coreYear', 'positionsYear'),
            ):
                self.assertEqual(
                    comparison['experiences'][kind][metric],
                    reference_counts(
                        gold['experiences'], group_experiences(predicted), reference_kind
                    ),
                )
        self.assertEqual(first['experiences']['positions']['core']['matched'], 3)
        self.assertEqual(first['experiences']['positions']['coreYear']['matched'], 4)
        self.assertEqual(second['experiences']['titles']['core']['matched'], 4)
        self.assertEqual(second['experiences']['titles']['core']['predicted'], 5)
        self.assertEqual(second['experiences']['companies']['core']['matched'], 3)
        self.assertEqual(second['experiences']['companies']['core']['predicted'], 4)
        pooled = _aggregate([], [first, second], [])
        self.assertEqual(pooled['experiences.companies.core.precision'], 6 / 7)
        self.assertEqual(pooled['experiences.companies.core.recall'], 1)
        self.assertEqual(pooled['experiences.titles.core.precision'], 8 / 9)
        self.assertEqual(pooled['experiences.titles.core.recall'], 1)
        self.assertEqual(pooled['experiences.positions.core.precision'], 6 / 9)
        self.assertEqual(pooled['experiences.positions.core.recall'], 6 / 8)
        self.assertEqual(pooled['experiences.positions.coreYear.precision'], 8 / 9)
        self.assertEqual(pooled['experiences.positions.coreYear.recall'], 1)
        report = {
            'configurations': [
                {'name': 'synthetic', 'metrics': pooled, 'delta': dict.fromkeys(pooled, 0)}
            ]
        }
        output = io.StringIO()
        with redirect_stdout(output):
            print_bench(report)
        cells = {
            line.split('\t')[0]: line.split('\t')[1:] for line in output.getvalue().splitlines()[1:]
        }
        for key in (
            'experiences.positions.core.precision',
            'experiences.positions.coreYear.recall',
            'personal.fullName.core.correct',
        ):
            self.assertIn(key, cells)
            self.assertEqual(cells[key][2:], ['-', '-'])
        for marker in ('Vân Tinh', 'Tên Mẫu', 'Alias', 'Mây Đỏ', str(self.root)):
            self.assertNotIn(marker, output.getvalue() + json.dumps(report, ensure_ascii=False))

    def test_name_categories_and_empty_experience_denominators(self):
        for expected, actual, category in (
            ('Mr. Tên Mẫu (Alias)', 'Tên Mẫu', 'correct'),
            ('Tên Mẫu', 'Tên Khác', 'wrong'),
            ('Tên Mẫu', None, 'missing'),
            (None, 'Tên Mẫu', 'extra'),
            ('Ms. (Alias)', None, 'correct'),
        ):
            gold = grouped.record([])
            gold['personal']['fullName'] = expected
            metrics = self.pair(gold, [], actual)
            self.assertEqual(
                metrics['personal']['fullName']['core'],
                {key: int(key == category) for key in ('correct', 'wrong', 'missing', 'extra')},
            )
            for kind in ('companies', 'titles', 'positions'):
                self.assertEqual(
                    metrics['experiences'][kind]['core'],
                    {'matched': 0, 'predicted': 0, 'gold': 0, 'precision': 0.0, 'recall': 0.0},
                )
            self.assertEqual(
                metrics['experiences']['positions']['coreYear'],
                {'matched': 0, 'predicted': 0, 'gold': 0, 'precision': 0.0, 'recall': 0.0},
            )


if __name__ == '__main__':
    unittest.main()
