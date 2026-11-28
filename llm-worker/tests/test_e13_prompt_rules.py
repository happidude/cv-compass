"""E13 C acceptance with invented text and offline providers only."""

import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from dataclasses import replace
from itertools import product
from pathlib import Path
from unittest.mock import patch

from test_a1_clean_names import ENV, L, T, settings
from test_core import block, document, experience, personal, response
from test_o5_lines import line_personal, numbered, position
from test_pull import TOKEN, config

from cv_compass_llm.cli import CLI_ERRORS, parser, run_batch
from cv_compass_llm.config import (
    BENCH_ENV_KEYS,
    ENV_KEYS,
    PullConfigError,
    load_bench_config,
    load_config,
    load_pull_config,
)
from cv_compass_llm.names import trim_title
from cv_compass_llm.pipeline import extract
from cv_compass_llm.prompts import (
    BEGIN,
    END,
    build_experience_prompt,
    build_lines_prompt,
    build_whole_prompt,
    prompt_version,
)
from cv_compass_llm.providers import FakeProvider
from cv_compass_llm.pull import run_pull
from cv_compass_llm.validate import validate_experiences, validate_line_experiences

NON_SPLIT = (
    'Director, Creative Merchandising',
    'Area Sales Manager | Traditional Trade Channel',
    'Manager, Category Management – Home & Living',
    'EAF, LF, CCM supervisor',
)
PERIOD = '2020 - 2022'
COMPANY = 'NORTHWIND TRADING'


class PromptRulesTests(unittest.TestCase):
    def test_v1_existing_hashes_and_v3_hashes(self):
        cases = (
            (
                build_experience_prompt,
                T,
                {},
                '02006f1cc2543a66b28b9e3f67490160d78dc9e75619a49378142998bb171d5b',
                'bd7dc435ba920bb2980b3d27fee4dd81bd64593b45a1066deea82700ac266856',
            ),
            (
                build_whole_prompt,
                T,
                {},
                '817ebee4c39aa00398b15ef681da2d5c7c6112b2f9946b0e2e0bafd77ab163ed',
                '9b185cce0c7ad0db1bfd6a6ad07fbdac809a59a965c1de54a818cd5dd39950b8',
            ),
            (
                build_lines_prompt,
                L,
                {},
                '4c9d65ffa8c3e47d48c708b0c4cf312867a99de9516fafa075a5551d21c4a470',
                '33bb0ce92cf03b38b71c007232c5bd3fca9326dfb70fc3ad0697ea000518709d',
            ),
            (
                build_lines_prompt,
                L,
                {'whole': True},
                '64240b9c6c7d5edb5dbf82815a428525303ff1875ab46d692ad42ccc5534e62b',
                '13f1685a4a37bcd8716ddc5f047a555e799ab00c8c7d77e98f5d59fe6e9fd973',
            ),
        )
        for builder, text, options, v1_hash, v3_hash in cases:
            for version, expected in (('v1', v1_hash), ('v3', v3_hash)):
                with self.subTest(builder=builder.__name__, options=options, version=version):
                    prompt = builder(text, prompt_rules=version, **options)
                    self.assertEqual(hashlib.sha256(prompt.encode()).hexdigest(), expected)
            self.assertEqual(builder(text, **options), builder(text, prompt_rules='v1', **options))

    def test_v3_rules_and_data_preserved_in_all_templates(self):
        raw = (
            T
            + '\n'
            + '\n'.join(NON_SPLIT)
            + '\n'
            + BEGIN
            + END
            + '\nChép title/company đúng chữ hoa-thường như CV. '
            + 'Quote period phải chứa nguyên văn token ngày trong CV.'
        )
        for builder, options in (
            (build_experience_prompt, {}),
            (build_whole_prompt, {}),
            (build_whole_prompt, {'whole_personal': False}),
            (build_lines_prompt, {}),
            (build_lines_prompt, {'whole': True}),
        ):
            for schema_text, name_style in product((True, False), ('raw', 'clean')):
                with self.subTest(
                    builder=builder.__name__,
                    options=options,
                    schema_text=schema_text,
                    name_style=name_style,
                ):
                    common = dict(schema_text=schema_text, name_style=name_style, **options)
                    old = builder(raw, **common)
                    instructions, data = builder(raw, prompt_rules='v3', **common).split(BEGIN, 1)
                    self.assertEqual(data, old.split(BEGIN, 1)[1])
                    escaped = raw.replace(BEGIN, '[CV_BEGIN_ESCAPED]').replace(
                        END, '[CV_END_ESCAPED]'
                    )
                    self.assertEqual(data, '\n' + escaped + '\n' + END)
                    for example in NON_SPLIT:
                        self.assertIn(example, instructions)
                    for rule in (
                        '"A, B", "A | B", "A / B", "A cum B" or "A kiêm B"',
                        'two distinct roles',
                        'same company and period',
                        'Sales Manager cum Marketing Manager',
                        'verbatim contiguous substring',
                        'organization, never a description, location or skill',
                        '"X at/of Y", company is Y',
                        'company "Mesa Distributor"',
                        'two-column layouts',
                        'summary tables plus details',
                        'experience at the top of a page',
                        'each position exactly once',
                    ):
                        self.assertIn(rule, instructions)
                    if builder is build_lines_prompt:
                        self.assertIn('reuse the period {line, text}', instructions)
                        self.assertIn('each title as {line, value}', instructions)
                        self.assertNotIn('period quote', instructions)
        for lean, style in product((True, False), ('raw', 'clean')):
            options = dict(personal=True, schema_text=not lean, name_style=style)
            self.assertEqual(
                build_lines_prompt(L, prompt_rules='v3', **options),
                build_lines_prompt(L, **options),
            )

    def test_version_suffix_follows_existing_suffixes(self):
        for mode, evidence, clean, lean, xp, trim in product(
            ('blocks', 'whole'),
            ('quote', 'lines'),
            (False, True),
            (False, True),
            (False, True),
            (False, True),
        ):
            old = settings(
                evidence=evidence,
                name_style='clean' if clean else 'raw',
                prompt_schema_text=not lean,
                whole_personal=not xp,
                trim_input=trim,
                trim_sections=trim,
            )
            self.assertEqual(
                prompt_version(mode, replace(old, prompt_rules='v3')),
                prompt_version(mode, old) + '-r3',
            )
        self.assertEqual(prompt_version('blocks'), prompt_version('blocks', settings()))

    def test_pipeline_keeps_both_compound_roles_and_personal_prompt_unchanged(self):
        raw = (
            'Synthetic Person\nExperience\nSales Manager cum Marketing Manager\n'
            + COMPANY
            + '\n'
            + PERIOD
        )
        titles = ('Sales Manager', 'Marketing Manager')
        for mode, evidence, rules in product(('blocks', 'whole'), ('quote', 'lines'), ('v1', 'v3')):
            with self.subTest(mode=mode, evidence=evidence, rules=rules):
                if evidence == 'lines':
                    items = [position(3, 4, 5) for _ in titles]
                    for item, title in zip(items, titles, strict=True):
                        item['title']['value'], item['company']['value'] = title, COMPANY
                    pi = line_personal()
                else:
                    items = [
                        experience(title, COMPANY, '2020', '2022', period=PERIOD)
                        for title in titles
                    ]
                    pi = personal()
                xp = {'experiences': items}
                responses = [xp, pi] if mode == 'whole' else [pi, xp]
                provider = FakeProvider(responses)
                options = settings(evidence=evidence, whole_personal=False, prompt_rules=rules)
                result = extract(document(raw), provider, mode=mode, config=options)
                self.assertEqual([row.title for row in result.experiences], list(titles))
                self.assertTrue(all(row.company == COMPANY for row in result.experiences))
                self.assertTrue(
                    all((row.start, row.end) == ('2020', '2022') for row in result.experiences)
                )
                self.assertEqual(
                    result.experiences[0].evidence['period'],
                    result.experiences[1].evidence['period'],
                )
                for row in result.experiences:
                    for span in row.evidence.values():
                        self.assertEqual(raw[span.start : span.end], span.text)
                legacy = FakeProvider(responses)
                extract(
                    document(raw), legacy, mode=mode, config=replace(options, prompt_rules='v1')
                )
                personal_index = 1 if mode == 'whole' else 0
                self.assertEqual(provider.prompts[personal_index], legacy.prompts[personal_index])
                xp_index = 1 - personal_index
                self.assertEqual('two distinct roles' in provider.prompts[xp_index], rules == 'v3')


class TitleCompanyCoreTests(unittest.TestCase):
    def test_company_core_cuts_only_matching_suffix_and_keeps_raw_offsets(self):
        for connector, suffix in product(
            (' – ', ' - ', ' | '),
            (
                'Northwind Trading Co., Ltd',
                'Công ty Cổ phần Northwind Trading Việt Nam',
                'TNHH Northwind Trading JSC Corp Corporation Company Group Vietnam VN',
                'Northwind Trading Co.',
            ),
        ):
            title = 'Sales Lead' + connector + suffix
            left, right = trim_title(title, COMPANY, prompt_rules='v3')
            self.assertEqual(title[left:right], 'Sales Lead')
            left, right = trim_title(title, 'Công ty ' + COMPANY + ' TNHH', prompt_rules='v3')
            self.assertEqual(title[left:right], 'Sales Lead')
        for rules, trim in product(('v1', 'v3'), ('off', 'on')):
            title = 'Sales Lead – Northwind Trading Co., Ltd'
            raw = '🧪 prefix\n' + title + '\n' + COMPANY + '\n' + PERIOD
            item = experience(title, COMPANY, '2020', '2022', period=PERIOD)
            line = position(2, 3, 4)
            line['title']['value'], line['company']['value'] = title, COMPANY
            options = dict(name_trim=trim, prompt_rules=rules)
            for rows, issues in (
                validate_experiences(response(item), block(raw), **options),
                validate_line_experiences(response(line), numbered(raw), **options),
            ):
                self.assertFalse(issues)
                expected = 'Sales Lead' if trim == 'on' and rules == 'v3' else title
                self.assertEqual(rows[0].title, expected)
                span = rows[0].evidence['title']
                self.assertEqual(span.start, raw.index(title))
                self.assertEqual(raw[span.start : span.end], expected)
                self.assertEqual(span.text, expected)

    def test_scope_different_company_empty_core_and_empty_cut_are_retained(self):
        for title, company in (
            (NON_SPLIT[2], COMPANY),
            ('Sales Lead – Northwind Trading Services', COMPANY),
            ('Sales Lead - Group', 'Company'),
            (' – Northwind Trading Co., Ltd', COMPANY),
        ):
            left, right = trim_title(title, company, prompt_rules='v3')
            self.assertEqual(title[left:right], title)
        title = 'Sales Lead – Northwind Trading Co., Ltd'
        self.assertEqual(trim_title(title, COMPANY), trim_title(title, COMPANY, prompt_rules='v1'))

    def test_old_separator_rules_keep_the_same_span_after_company_core_cut(self):
        for title in (
            NON_SPLIT[1],
            'A | scope',
            'A / scope',
            'A | B | Northwind Trading',
        ):
            with self.subTest(title=title):
                old = trim_title(title, COMPANY, prompt_rules='v1')
                self.assertEqual(trim_title(title, COMPANY, prompt_rules='v3'), old)

    def test_company_core_does_not_drop_co_op_or_match_short_cores(self):
        for title, company in (
            ('Sales Lead - Op Mart', 'Co-op Mart'),
            ('Sales Lead - M', 'Group M'),
            ('Sales Lead – AB', 'Group AB'),
            ('Sales Lead - A B', 'Group A B'),
        ):
            with self.subTest(title=title, company=company):
                left, right = trim_title(title, company, prompt_rules='v3')
                self.assertEqual(title[left:right], title)

    def test_pipeline_passes_v3_to_quote_and_lines_validation_in_both_modes(self):
        title = 'Sales Lead – Northwind Trading Co., Ltd'
        raw = 'Synthetic Person\nExperience\n' + title + '\n' + COMPANY + '\n' + PERIOD
        for mode, evidence, trim in product(
            ('blocks', 'whole'),
            ('quote', 'lines'),
            ('off', 'on'),
        ):
            if evidence == 'lines':
                item = position(3, 4, 5)
                item['title']['value'], item['company']['value'] = title, COMPANY
                pi = line_personal()
            else:
                item = experience(title, COMPANY, '2020', '2022', period=PERIOD)
                pi = personal()
            xp = {'experiences': [item]}
            provider = FakeProvider([xp, pi] if mode == 'whole' else [pi, xp])
            result = extract(
                document(raw),
                provider,
                mode=mode,
                config=settings(
                    evidence=evidence,
                    whole_personal=False,
                    name_trim=trim,
                    prompt_rules='v3',
                ),
            )
            expected = 'Sales Lead' if trim == 'on' else title
            self.assertEqual([row.title for row in result.experiences], [expected])


class PromptRulesConfigTests(unittest.TestCase):
    def test_defaults_precedence_bench_cli_and_pull_errors(self):
        key = 'CVC_LLM_PROMPT_RULES'
        self.assertEqual(ENV_KEYS['prompt_rules'], key)
        self.assertIn(key, BENCH_ENV_KEYS)
        self.assertEqual(settings().prompt_rules, 'v1')
        self.assertEqual(
            load_config({}, ENV, None, env_file_explicit=False, _file_values={}).prompt_rules,
            'v1',
        )
        self.assertIn('INVALID_PROMPT_RULES', CLI_ERRORS)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.env'
            path.write_text(key + '=v3\n', encoding='utf-8')
            self.assertEqual(load_bench_config(path, ENV).prompt_rules, 'v3')
            self.assertEqual(load_config({}, ENV, path, env_file_explicit=True).prompt_rules, 'v3')
            pull_env = ENV | {
                'CVC_LLM_BACKEND_URL': 'http://localhost',
                'CVC_LLM_WORKER_TOKEN': TOKEN,
            }
            for rules in ('v1', 'v3'):
                env = ENV | {key: rules}
                self.assertEqual(
                    load_config({}, env, path, env_file_explicit=True).prompt_rules, rules
                )
                opposite = 'v1' if rules == 'v3' else 'v3'
                loaded = load_config(
                    {'prompt_rules': rules}, ENV | {key: opposite}, path, env_file_explicit=True
                )
                self.assertEqual(loaded.prompt_rules, rules)
                self.assertEqual(
                    load_pull_config(
                        pull_env | {key: rules}, path, env_file_explicit=True
                    ).llm.prompt_rules,
                    rules,
                )
            for invalid in ('v2', 'V3', 'other'):
                with self.assertRaisesRegex(ValueError, '^INVALID_PROMPT_RULES$'):
                    load_config({'prompt_rules': invalid}, ENV, path, env_file_explicit=True)
                with self.assertRaisesRegex(
                    PullConfigError, '^CVC_LLM_PROMPT_RULES INVALID_PROMPT_RULES$'
                ):
                    load_pull_config(pull_env | {key: invalid}, path, env_file_explicit=True)
            for command in (
                ['run', '--input', 'synthetic-input', '--output', 'synthetic-output'],
                ['pull'],
            ):
                self.assertEqual(
                    parser().parse_args(command + ['--prompt-rules', 'v3']).prompt_rules, 'v3'
                )

    def test_run_summary_and_worker_start_record_flag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, repo = root / 'input', root / 'repo'
            source.mkdir()
            repo.mkdir()
            (source / 'synthetic.json').write_text(
                json.dumps({'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': T}]}),
                encoding='utf-8',
            )
            for rules in ('v1', 'v3'):
                summary = run_batch(
                    source,
                    root / rules,
                    FakeProvider([personal(), {'experiences': []}]),
                    config=settings(prompt_rules=rules),
                    repo_root=repo,
                )
                self.assertEqual(summary['prompt_rules'], rules)
                saved = json.loads((root / rules / 'run-summary.json').read_text(encoding='utf-8'))
                self.assertEqual(saved['prompt_rules'], rules)
        pull = config('http://localhost')
        pull = replace(pull, llm=replace(pull.llm, prompt_rules='v3'))
        with (
            patch('cv_compass_llm.pull.BackendClient') as client,
            patch('cv_compass_llm.pull.event') as event,
            redirect_stdout(io.StringIO()),
        ):
            client.return_value.request.return_value = (204, None)
            self.assertEqual(run_pull(pull, FakeProvider(personal()), once=True), 0)
        start = next(call for call in event.call_args_list if call.args == ('worker_start',))
        self.assertEqual(start.kwargs['prompt_rules'], 'v3')


if __name__ == '__main__':
    unittest.main()
