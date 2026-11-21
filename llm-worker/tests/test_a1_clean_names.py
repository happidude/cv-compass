"""A1 acceptance with invented data and offline providers only."""

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

from test_core import document, experience, personal
from test_o5_lines import line_personal
from test_pull import TOKEN, Backend, backend_server, claim, config

from cv_compass_llm.bench import run_bench
from cv_compass_llm.cli import main, parser
from cv_compass_llm.config import (
    BENCH_ENV_KEYS,
    ENV_KEYS,
    load_bench_config,
    load_config,
    load_pull_config,
)
from cv_compass_llm.pipeline import extract
from cv_compass_llm.prompts import (
    BEGIN,
    CLEAN_NAME_INSTRUCTIONS,
    CLEAN_NAME_LINES_INSTRUCTIONS,
    END,
    build_experience_prompt,
    build_lines_prompt,
    build_personal_prompt,
    build_whole_prompt,
    prompt_version,
)
from cv_compass_llm.providers import FakeProvider, ProviderError
from cv_compass_llm.pull import run_pull
from cv_compass_llm.pull_payload import WORKER_ISSUES, compact_result
from cv_compass_llm.schema import EXPERIENCE_SCHEMA, PERSONAL_SCHEMA, Issue

T = 'Synthetic Person\nExperience\nRole-Z at Org-Z\n01/2020 - 02/2021'
L = '[L1] Synthetic Person\n[L2] Role-Z\n[L3] Org-Z\n[L4] 2020 - 2021'
ENV = {'CVC_LLM_PROVIDER': 'ollama', 'CVC_LLM_MODEL': 'synthetic-model'}


def settings(**options):
    return replace(config('http://localhost').llm, **options)


class PromptTests(unittest.TestCase):
    def test_raw_sha256_locks_implicit_and_explicit(self):
        cases = (
            (
                build_experience_prompt,
                T,
                {},
                '02006f1cc2543a66b28b9e3f67490160d78dc9e75619a49378142998bb171d5b',
            ),
            (
                build_experience_prompt,
                T,
                {'schema_text': False},
                '296921a250348b5208dd3146186d067b01be52af3fc24b6bf09cc07097c7925d',
            ),
            (
                build_whole_prompt,
                T,
                {},
                '817ebee4c39aa00398b15ef681da2d5c7c6112b2f9946b0e2e0bafd77ab163ed',
            ),
            (
                build_whole_prompt,
                T,
                {'whole_personal': False},
                'cd59b2641bb022a338cdbf836fe24718e98bd97f4acdcd45dc445fff3d7499b8',
            ),
            (
                build_lines_prompt,
                L,
                {},
                '4c9d65ffa8c3e47d48c708b0c4cf312867a99de9516fafa075a5551d21c4a470',
            ),
            (
                build_lines_prompt,
                L,
                {'whole': True},
                '64240b9c6c7d5edb5dbf82815a428525303ff1875ab46d692ad42ccc5534e62b',
            ),
            (
                build_lines_prompt,
                L,
                {'personal': True},
                '41562d8aac5eba6ce854ceabe6e0d7cd2a61b6315140002c2025c0dca249ab2f',
            ),
            (
                build_personal_prompt,
                T,
                {},
                '004aa5841a83447adc1ed9f822fbfca341405a95078e6fe86623540e77a72521',
            ),
        )
        for builder, text, options, expected in cases:
            styles = ({},) if builder is build_personal_prompt else ({}, {'name_style': 'raw'})
            for style in styles:
                with self.subTest(builder=builder.__name__, options=options, style=style):
                    prompt = builder(text, **options, **style)
                    self.assertEqual(hashlib.sha256(prompt.encode()).hexdigest(), expected)

    def test_raw_provider_calls_match_default_and_unconfigured(self):
        for mode, evidence, lean, xp in product(
            ('blocks', 'whole'), ('quote', 'lines'), (False, True), (False, True)
        ):
            options = dict(evidence=evidence, prompt_schema_text=not lean, whole_personal=not xp)
            responses = (
                [{'experiences': []}, line_personal()]
                if evidence == 'lines' and mode == 'whole'
                else [line_personal(), {'experiences': []}]
                if evidence == 'lines'
                else [
                    dict(personal(), experiences=[]) if not xp else {'experiences': []},
                    personal(),
                ]
                if mode == 'whole'
                else [personal(), {'experiences': []}]
            )
            baseline = FakeProvider(responses)
            explicit = FakeProvider(responses)
            extract(document(T), baseline, mode=mode, config=settings(**options))
            extract(document(T), explicit, mode=mode, config=settings(name_style='raw', **options))
            with self.subTest(mode=mode, options=options):
                self.assertEqual(baseline.prompts, explicit.prompts)
                self.assertEqual(baseline.calls, explicit.calls)
                self.assertEqual(baseline.request_options, explicit.request_options)
            if evidence == 'quote' and not lean and not xp:
                legacy = FakeProvider(responses)
                extract(document(T), legacy, mode=mode)
                self.assertEqual(baseline.prompts, legacy.prompts)
                self.assertEqual(baseline.calls, legacy.calls)

    def test_clean_adds_one_paragraph_and_preserves_data(self):
        for builder, options in (
            (build_experience_prompt, {}),
            (build_whole_prompt, {}),
            (build_whole_prompt, {'whole_personal': False}),
            (build_lines_prompt, {}),
            (build_lines_prompt, {'whole': True}),
        ):
            for schema_text in (True, False):
                with self.subTest(builder=builder.__name__, options=options, lean=not schema_text):
                    raw = builder(T, schema_text=schema_text, **options)
                    clean = builder(T, schema_text=schema_text, name_style='clean', **options)
                    instructions, data = clean.split(BEGIN, 1)
                    paragraph = (
                        CLEAN_NAME_LINES_INSTRUCTIONS
                        if builder is build_lines_prompt
                        else CLEAN_NAME_INSTRUCTIONS
                    )
                    self.assertEqual(instructions.count(paragraph), 1)
                    if builder is build_lines_prompt:
                        self.assertNotIn('quote', paragraph.casefold())
                        self.assertIn(
                            'value must be a short verbatim substring of that line', paragraph
                        )
                    self.assertEqual(data, raw.split(BEGIN, 1)[1])
                    self.assertEqual(data, '\n' + T + '\n' + END)
                    self.assertEqual(clean.replace(paragraph + '\n', ''), raw)

    def test_clean_never_changes_personal_prompts(self):
        for schema_text in (True, False):
            provider = FakeProvider(personal())
            extract(
                document(T),
                provider,
                max_blocks=0,
                config=settings(name_style='clean', prompt_schema_text=schema_text),
            )
            self.assertEqual(
                provider.prompts[0],
                build_personal_prompt(T, schema_text=schema_text),
            )
            self.assertEqual(
                build_lines_prompt(L, personal=True, schema_text=schema_text, name_style='clean'),
                build_lines_prompt(L, personal=True, schema_text=schema_text),
            )

    def test_clean_pipeline_wiring_for_each_experience_call(self):
        for mode, evidence in product(('blocks', 'whole'), ('quote', 'lines')):
            header = line_personal() if evidence == 'lines' else personal()
            whole = {'experiences': []}
            if evidence == 'quote':
                whole.update(personal())
            responses = [header, {'experiences': []}] if mode == 'blocks' else [whole, header]
            provider = FakeProvider(responses)
            extract(
                document(T),
                provider,
                mode=mode,
                config=settings(name_style='clean', evidence=evidence),
            )
            with self.subTest(mode=mode, evidence=evidence):
                self.assertEqual(len(provider.prompts), 2)
                experience_index = 1 if mode == 'blocks' else 0
                paragraph = (
                    CLEAN_NAME_LINES_INSTRUCTIONS
                    if evidence == 'lines'
                    else CLEAN_NAME_INSTRUCTIONS
                )
                self.assertIn(paragraph, provider.prompts[experience_index])
                self.assertNotIn(paragraph, provider.prompts[1 - experience_index])

    def test_version_suffix_order_and_raw_versions(self):
        for mode, evidence, lean, xp, trim, sections in product(
            ('blocks', 'whole'),
            ('quote', 'lines'),
            (False, True),
            (False, True),
            (False, True),
            (False, True),
        ):
            options = dict(
                evidence=evidence,
                prompt_schema_text=not lean,
                whole_personal=not xp,
                trim_input=trim,
                trim_sections=sections,
            )
            base = (
                ('whole-lines-2' if mode == 'whole' else 'lines-2')
                if evidence == 'lines'
                else ('whole-2' if mode == 'whole' else '3')
            )
            suffix = ('-lean' if lean else '') + ('-xp' if mode == 'whole' and xp else '')
            suffix += ('-trims' if sections else '-trim') if trim else ''
            for style in ('raw', 'clean'):
                expected = (
                    'llm-extraction-prompt/'
                    + base
                    + ('-clean' if style == 'clean' else '')
                    + suffix
                )
                self.assertEqual(
                    prompt_version(mode, settings(name_style=style, **options)), expected
                )

    def test_clean_still_rejects_invented_values_and_nonadjacent_parts(self):
        for evidence in ('quote', 'lines'):
            for bad in ('Absent Name', 'Role-Z Org-Z'):
                row = experience(title=bad, company=bad, start=None, end=None, period=None)
                row['quotes'].update(title='Role-Z at Org-Z', company='Role-Z at Org-Z')
                if evidence == 'lines':
                    row = {
                        'title': {'line': 3, 'value': bad},
                        'company': {'line': 3, 'value': bad},
                        'period': {'line': None, 'text': None},
                        'ongoing': False,
                    }
                provider = FakeProvider(
                    [
                        line_personal() if evidence == 'lines' else personal(),
                        {'experiences': [row]},
                    ]
                )
                result = extract(
                    document(T), provider, config=settings(name_style='clean', evidence=evidence)
                )
                self.assertFalse(result.experiences)
                for field in ('title', 'company'):
                    self.assertIn(Issue('QUOTE_NOT_FOUND', field), result.issues)


class PersonalEmptyTests(unittest.TestCase):
    def test_null_personal_is_reported_for_each_requesting_call(self):
        for mode, evidence, style in product(
            ('blocks', 'whole'), ('quote', 'lines'), ('raw', 'clean')
        ):
            header = line_personal() if evidence == 'lines' else personal()
            whole = {'experiences': []} if evidence == 'lines' else dict(personal(), experiences=[])
            responses = [header] if mode == 'blocks' else [whole, header]
            result = extract(
                document('Synthetic Person\nExperience\n'),
                FakeProvider(responses),
                mode=mode,
                config=settings(name_style=style, evidence=evidence),
            )
            with self.subTest(mode=mode, evidence=evidence, style=style):
                self.assertEqual(result.issues, (Issue('PERSONAL_EMPTY'),))

    def test_whole_empty_personal_does_not_flag_valid_header(self):
        named = personal(fullName={'value': 'Synthetic Person', 'quote': 'Synthetic Person'})
        for style in ('raw', 'clean'):
            provider = FakeProvider([dict(personal(), experiences=[]), named])
            result = extract(
                document('Synthetic Person\nExperience\n'),
                provider,
                mode='whole',
                config=settings(name_style=style),
            )
            self.assertEqual(result.issues, ())
            self.assertEqual(result.fields[0].value, 'Synthetic Person')

    def test_no_letters_never_reports_empty_even_with_numbered_line_markers(self):
        for mode, evidence in product(('blocks', 'whole'), ('quote', 'lines')):
            header = line_personal() if evidence == 'lines' else personal()
            whole = {'experiences': []} if evidence == 'lines' else dict(personal(), experiences=[])
            responses = [header] if mode == 'blocks' else [whole, header]
            result = extract(
                document('1234 / ---'),
                FakeProvider(responses),
                mode=mode,
                max_blocks=0,
                config=settings(evidence=evidence),
            )
            self.assertNotIn(Issue('PERSONAL_EMPTY'), result.issues)

    def test_provider_invalid_json_and_truncation_do_not_report_empty(self):
        for mode, evidence, kind in product(
            ('blocks', 'whole'), ('quote', 'lines'), ('provider', 'json', 'schema', 'truncated')
        ):
            count = 2 if mode == 'whole' else 1
            valid = line_personal() if evidence == 'lines' else personal()
            whole = {'experiences': []} if evidence == 'lines' else dict(personal(), experiences=[])
            responses = [valid] if count == 1 else [whole, valid]
            if kind != 'truncated':
                response = (
                    ProviderError('SYNTHETIC_ERROR')
                    if kind == 'provider'
                    else '{'
                    if kind == 'json'
                    else '{}'
                )
                responses = [response] * count
            provider = FakeProvider(
                responses, metrics={'done_reason': 'length'} if kind == 'truncated' else {}
            )
            result = extract(
                document('Synthetic Person\nExperience\n'),
                provider,
                mode=mode,
                config=settings(evidence=evidence),
            )
            self.assertNotIn(Issue('PERSONAL_EMPTY'), result.issues)
            expected = (
                'PROVIDER_ERROR'
                if kind == 'provider'
                else 'OUTPUT_TRUNCATED'
                if kind == 'truncated'
                else 'LLM_INVALID_JSON'
            )
            self.assertTrue(any(issue.code == expected for issue in result.issues))

    def test_values_rejected_by_validation_count_as_empty(self):
        response = personal(fullName={'value': 'Absent Person', 'quote': 'Absent Person'})
        result = extract(document('Synthetic Person\nExperience\n'), FakeProvider(response))
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'fullName'), result.issues)
        self.assertIn(Issue('PERSONAL_EMPTY'), result.issues)

    def test_contacts_do_not_hide_empty_model_personal(self):
        result = extract(
            document('invented@example.invalid\nExperience\n'), FakeProvider(personal())
        )
        self.assertTrue(any(field.key == 'email' for field in result.fields))
        self.assertIn(Issue('PERSONAL_EMPTY'), result.issues)

    def test_valid_personal_values_prevent_empty_issue(self):
        for mode, evidence in product(('blocks', 'whole'), ('quote', 'lines')):
            value = {'value': 'Synthetic Person', 'quote': 'Synthetic Person'}
            header = (
                line_personal(fullName={'line': 1, 'value': 'Synthetic Person'})
                if evidence == 'lines'
                else personal(fullName=value)
            )
            whole = (
                {'experiences': []}
                if evidence == 'lines'
                else dict(personal(fullName=value), experiences=[])
            )
            responses = [header] if mode == 'blocks' else [whole, header]
            result = extract(
                document('Synthetic Person\nExperience\n'),
                FakeProvider(responses),
                mode=mode,
                config=settings(evidence=evidence),
            )
            self.assertNotIn(Issue('PERSONAL_EMPTY'), result.issues)

    def test_xp_whole_does_not_report_empty_but_header_still_does(self):
        named = personal(fullName={'value': 'Synthetic Person', 'quote': 'Synthetic Person'})
        for header, expected in ((personal(), 1), (named, 0)):
            result = extract(
                document('Synthetic Person\nExperience\n'),
                FakeProvider([{'experiences': []}, header]),
                mode='whole',
                config=settings(whole_personal=False),
            )
            self.assertEqual(result.issues.count(Issue('PERSONAL_EMPTY')), expected)

    def test_empty_issue_is_absent_from_backend_payload(self):
        result = extract(document('Synthetic Person\nExperience\n'), FakeProvider(personal()))
        self.assertIn(Issue('PERSONAL_EMPTY'), result.issues)
        self.assertNotIn('PERSONAL_EMPTY', WORKER_ISSUES)
        body = compact_result(result, claim(), {})
        self.assertEqual(body, compact_result(replace(result, issues=()), claim(), {}))
        payload = json.loads(body)
        self.assertEqual(payload['issues'], [])
        self.assertTrue(all(value is None for value in payload['personal'].values()))


class ConfigAndReportingTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.env_file = self.root / 'synthetic.env'
        self.env_file.write_text('CVC_LLM_NAME_STYLE=clean\n', encoding='utf-8')

    def test_default_env_cli_precedence_and_invalid_value(self):
        missing = self.root / 'missing.env'

        def load(cli, env, path=missing):
            return load_config(cli, ENV | env, path, env_file_explicit=False)

        self.assertEqual(load({}, {}).name_style, 'raw')
        self.assertEqual(load({}, {}, self.env_file).name_style, 'clean')
        self.assertEqual(load({}, {'CVC_LLM_NAME_STYLE': 'raw'}, self.env_file).name_style, 'raw')
        self.assertEqual(
            load({'name_style': 'clean'}, {'CVC_LLM_NAME_STYLE': 'raw'}).name_style, 'clean'
        )
        with self.assertRaisesRegex(ValueError, '^INVALID_NAME_STYLE$'):
            load({}, {'CVC_LLM_NAME_STYLE': 'unknown-private-value'})

    def test_cli_option_and_safe_error_code(self):
        for command in ('run', 'pull'):
            arguments = (
                ['run', '--input', 'unused', '--output', 'unused'] if command == 'run' else ['pull']
            )
            self.assertEqual(
                parser().parse_args(arguments + ['--name-style', 'clean']).name_style, 'clean'
            )
        error = io.StringIO()
        with patch('cv_compass_llm.cli.os.environ', ENV), redirect_stdout(error):
            result = main(
                [
                    'run',
                    '--input',
                    'unused',
                    '--output',
                    'unused',
                    '--env-file',
                    str(self.env_file),
                    '--name-style',
                    'unknown-private-value',
                ]
            )
        self.assertEqual(result, 2)
        self.assertIn('INVALID_NAME_STYLE', error.getvalue())
        self.assertNotIn('unknown-private-value', error.getvalue())

    def test_bench_and_pull_config_wiring(self):
        self.assertEqual(ENV_KEYS['name_style'], 'CVC_LLM_NAME_STYLE')
        self.assertIn('CVC_LLM_NAME_STYLE', BENCH_ENV_KEYS)
        self.assertEqual(load_bench_config(self.env_file, ENV).name_style, 'clean')
        env = ENV | {'CVC_LLM_BACKEND_URL': 'http://localhost', 'CVC_LLM_WORKER_TOKEN': TOKEN}
        loaded = load_pull_config(env, self.env_file, env_file_explicit=True)
        self.assertEqual(loaded.llm.name_style, 'clean')
        self.env_file.write_text('CVC_LLM_NAME_STYLE=unknown-private-value\n', encoding='utf-8')
        self.assertEqual(prompt_version('blocks', loaded.llm), 'llm-extraction-prompt/3-clean')
        with self.assertRaisesRegex(ValueError, '^INVALID_NAME_STYLE$'):
            load_bench_config(self.env_file, ENV)
        with self.assertRaisesRegex(ValueError, '^CVC_LLM_NAME_STYLE INVALID_NAME_STYLE$'):
            load_pull_config(env, self.env_file, env_file_explicit=True)

    def test_pull_logs_style_and_counts_internal_issue(self):
        backend = Backend()
        backend.input = {'pages': [{'page': 1, 'raw_text': 'Synthetic Person\nExperience\n'}]}
        with backend_server(backend) as url, patch('cv_compass_llm.pull.event') as event:
            local = replace(config(url), llm=settings(name_style='clean'))
            self.assertEqual(run_pull(local, FakeProvider(personal()), once=True), 0)
        started = next(
            call.kwargs for call in event.call_args_list if call.args[0] == 'worker_start'
        )
        self.assertEqual(started['name_style'], 'clean')
        done = next(
            call.kwargs for call in event.call_args_list if call.args[0] == 'extraction_done'
        )
        self.assertIn('PERSONAL_EMPTY:1', done['issues'])
        payload = json.loads(backend.requests('result')[0][3])
        self.assertEqual(payload['issues'], [])
        self.assertEqual(payload['provenance']['promptVersion'], 'llm-extraction-prompt/3-clean')
        claimed = json.loads(backend.requests('claims')[0][3])
        self.assertEqual(claimed['provenance'], payload['provenance'])

    def test_bench_counts_empty_and_keeps_clean_config_for_warmup(self):
        source, gold, repo = (self.root / name for name in ('ocr', 'gold', 'repo'))
        for directory in (source, gold, repo):
            directory.mkdir()
        (source / 'synthetic.json').write_text(
            json.dumps({'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': T}]}),
            encoding='utf-8',
        )
        providers = []

        def factory(loaded):
            self.assertEqual(loaded.name_style, 'clean')
            self.assertEqual(loaded.mode, 'blocks')
            provider = FakeProvider([personal(), {'experiences': []}] * 2)
            providers.append(provider)
            return provider

        report = run_bench(
            source,
            gold,
            self.root / 'output',
            [f'clean={self.env_file}'],
            provider_factory=factory,
            environ=ENV,
            warmup=1,
            repo_root=repo,
        )
        row = report['configurations'][0]
        self.assertEqual(row['metrics']['issues.PERSONAL_EMPTY'], 1)
        self.assertEqual(row['metrics']['llm_calls'], 2)
        self.assertEqual(len(providers[0].prompts), 4)
        self.assertEqual(
            [call[0] for call in providers[0].calls],
            [PERSONAL_SCHEMA, EXPERIENCE_SCHEMA] * 2,
        )
        for call, prompt in zip(providers[0].calls, providers[0].prompts, strict=True):
            if call[0] == EXPERIENCE_SCHEMA:
                self.assertIn(CLEAN_NAME_INSTRUCTIONS, prompt)
            else:
                self.assertNotIn(CLEAN_NAME_INSTRUCTIONS, prompt)
