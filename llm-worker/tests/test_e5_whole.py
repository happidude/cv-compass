"""Whole-mode acceptance with synthetic CVs and loopback-only backend fixtures."""

import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from test_pull import NULL_PERSONAL, Backend, backend_server, config

from cv_compass_llm.cli import main, run_batch
from cv_compass_llm.config import PullConfigError, load_pull_config
from cv_compass_llm.ocr_input import Document, Page
from cv_compass_llm.pipeline import extract
from cv_compass_llm.prompts import (
    BEGIN,
    END,
    PROMPT_VERSION,
    WHOLE_PROMPT_VERSION,
    build_whole_prompt,
)
from cv_compass_llm.providers import FakeProvider, ProviderError
from cv_compass_llm.pull import run_pull
from cv_compass_llm.schema import PERSONAL_SCHEMA, WHOLE_SCHEMA, Issue
from cv_compass_llm.segmenter import Block, split_lines
from cv_compass_llm.validate import parse_response, validate_whole


def empty_response():
    return {**NULL_PERSONAL, 'experiences': []}


def document(text):
    return Document((Page(1, 'ok', text),))


def role():
    return {
        'title': 'Synthetic Engineer',
        'company': 'Synthetic Works',
        'start': '2020',
        'end': '2022',
        'ongoing': False,
        'quotes': {
            'title': 'Synthetic Engineer',
            'company': 'Synthetic Works',
            'period': '2020 - 2022',
        },
    }


def text_for_prompt_length(length):
    return 'x' * (length - len(build_whole_prompt('')))


class WholeTests(unittest.TestCase):
    def test_two_calls_all_ok_pages_personal_on_header_and_dedupe(self):
        pages = (
            Page(1, 'ok', 'Synthetic cover letter\nfirst@example.invalid'),
            Page(2, 'ok', '🧪 Synthetic Person\nGender: Other\n2000-12-31\n'),
            Page(3, 'failed', 'EXCLUDED_MARKER'),
            Page(4, 'ok', 'Education\nSynthetic Engineer\nSynthetic Works\n2020 - 2022'),
        )
        response = empty_response()
        response['personal'] = dict(
            NULL_PERSONAL['personal'],
            fullName={'value': 'Synthetic Person', 'quote': 'Synthetic Person'},
            gender={'value': 'OTHER', 'quote': 'Gender: Other'},
            dateOfBirth={'value': '2000-12-31', 'quote': '2000-12-31'},
        )
        response['experiences'] = [role(), role()]
        provider, progress = FakeProvider([response, {'personal': response['personal']}]), []
        with patch('cv_compass_llm.pipeline.segment', side_effect=AssertionError('segmented')):
            result = extract(
                Document(pages),
                provider,
                mode='whole',
                whole_timeout=123,
                max_blocks=0,
                on_block=lambda *args: progress.append(args),
            )
        self.assertEqual(provider.calls, [(WHOLE_SCHEMA, 8192, 123), (PERSONAL_SCHEMA, None, None)])
        self.assertEqual(len(provider.prompts), 2)
        for page in (pages[0], pages[1], pages[3]):
            self.assertIn(page.raw_text, provider.prompts[0])
        self.assertNotIn('EXCLUDED_MARKER', provider.prompts[0])
        self.assertEqual(result.prompt_version, WHOLE_PROMPT_VERSION)
        self.assertEqual(result.provider_name, 'fake')
        self.assertFalse(result.issues)
        fields = {field.key: field for field in result.fields}
        self.assertEqual(fields['fullName'].value, 'Synthetic Person')
        self.assertEqual(fields['gender'].value, 'OTHER')
        self.assertEqual(fields['dateOfBirth'].value, '2000-12-31')
        self.assertEqual(fields['email'].evidence.page, 1)
        for key in ('fullName', 'gender', 'dateOfBirth'):
            evidence = fields[key].evidence
            self.assertEqual(evidence.page, 2)
            self.assertEqual(pages[1].raw_text[evidence.start : evidence.end], evidence.text)
        self.assertEqual(len(result.experiences), 1)
        for evidence in result.experiences[0].evidence.values():
            self.assertEqual(evidence.page, 4)
            self.assertEqual(pages[3].raw_text[evidence.start : evidence.end], evidence.text)
        self.assertEqual([entry[:2] for entry in progress], [(1, 2), (2, 2)])
        self.assertGreaterEqual(progress[0][2], 0)

    def test_prompt_rules_and_delimiter_escaping(self):
        prompt = build_whole_prompt(BEGIN + END)
        for rule in (
            'cover letter',
            'cover page',
            'any page',
            'EVERY job position',
            'Copy quotes verbatim',
            'Unknown values must be null',
            'YYYY-MM',
            'Gender requires explicit',
            'exact letter case',
        ):
            self.assertIn(rule, prompt)
        self.assertEqual(prompt.count(BEGIN), 1)
        self.assertEqual(prompt.count(END), 1)
        self.assertIn('[CV_BEGIN_ESCAPED][CV_END_ESCAPED]', prompt)

    def test_context_boundaries(self):
        for tier, next_tier in ((8192, 16384), (16384, 24576), (24576, 32768), (32768, None)):
            boundary = int((tier - 4096) * 2.5)
            for delta, expected in ((-1, tier), (0, tier), (1, next_tier)):
                with self.subTest(tier=tier, delta=delta):
                    text = text_for_prompt_length(boundary + delta)
                    provider = FakeProvider(
                        [NULL_PERSONAL, {'experiences': []}]
                        if expected is None
                        else [empty_response(), NULL_PERSONAL]
                    )
                    result = extract(document(text), provider, mode='whole')
                    if expected is None:
                        self.assertIn(Issue('WHOLE_TOO_LONG'), result.issues)
                        self.assertTrue(all(call[1:] == (None, None) for call in provider.calls))
                    else:
                        self.assertEqual(
                            provider.calls,
                            [(WHOLE_SCHEMA, expected, 900), (PERSONAL_SCHEMA, None, None)],
                        )
                        self.assertNotIn(Issue('WHOLE_TOO_LONG'), result.issues)

    def test_fallback_matches_blocks_and_preserves_requested_version(self):
        text = 'Cover\nExperience\n' + 'Synthetic line\n' * 6000
        responses = [NULL_PERSONAL] + [{'experiences': []}] * 20
        blocks, fallback = FakeProvider(responses), FakeProvider(responses)
        expected = extract(document(text), blocks)
        actual = extract(document(text), fallback, mode='whole', whole_timeout=123)
        self.assertEqual(actual.fields, expected.fields)
        self.assertEqual(actual.experiences, expected.experiences)
        self.assertEqual(actual.issues, (Issue('WHOLE_TOO_LONG'), *expected.issues))
        self.assertEqual(actual.prompt_version, WHOLE_PROMPT_VERSION)
        self.assertEqual(expected.prompt_version, PROMPT_VERSION)
        self.assertEqual(fallback.prompts, blocks.prompts)
        self.assertEqual(fallback.calls, blocks.calls)

    def test_invalid_combined_json_is_one_issue_and_parsed_once(self):
        block = Block(tuple(split_lines((Page(1, 'ok', 'Synthetic source'),))))
        response = json.dumps(empty_response())
        with patch('cv_compass_llm.validate.parse_response', wraps=parse_response) as parsed:
            self.assertEqual(validate_whole(response, block), ((), (), ()))
        parsed.assert_called_once_with(response, WHOLE_SCHEMA)
        for response in (
            'not json',
            json.dumps(NULL_PERSONAL),
            json.dumps(dict(empty_response(), experiences='invalid')),
        ):
            with patch('cv_compass_llm.validate.parse_response', wraps=parse_response) as parsed:
                fields, experiences, issues = validate_whole(response, block)
            parsed.assert_called_once_with(response, WHOLE_SCHEMA)
            self.assertEqual((fields, experiences, issues), ((), (), (Issue('LLM_INVALID_JSON'),)))

    def test_cross_page_quotes_and_unsupported_dates_are_rejected(self):
        pages = (Page(1, 'ok', 'Synthetic'), Page(2, 'ok', 'Person\nSynthetic Engineer\n'))
        response = empty_response()
        response['personal'] = dict(
            NULL_PERSONAL['personal'],
            fullName={'value': 'Synthetic Person', 'quote': 'Synthetic Person'},
        )
        response['experiences'] = [dict(role(), company=None)]
        result = extract(
            Document(pages),
            FakeProvider([response, {'personal': response['personal']}]),
            mode='whole',
        )
        self.assertEqual(result.fields, ())
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'fullName'), result.issues)
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'period'), result.issues)
        self.assertIsNone(result.experiences[0].start)

    def test_provider_failure_completes_one_progress_step(self):
        progress = []
        result = extract(
            document('first@example.invalid'),
            FakeProvider([ProviderError('PRIVATE_FAILURE'), NULL_PERSONAL]),
            mode='whole',
            on_block=lambda *args: progress.append(args),
        )
        self.assertEqual(result.issues, (Issue('PROVIDER_ERROR'), Issue('PERSONAL_EMPTY')))
        self.assertEqual(len(result.fields), 1)
        self.assertEqual([row[:2] for row in progress], [(1, 2), (2, 2)])


class WholeIntegrationTests(unittest.TestCase):
    def test_pull_provenance_options_logs_and_internal_fallback_issue(self):
        for mode, fallback in (('blocks', False), ('whole', False), ('whole', True)):
            with self.subTest(mode=mode, fallback=fallback):
                backend = Backend()
                if fallback:
                    backend.input['pages'][0]['raw_text'] = (
                        'Cover\nExperience\n' + 'Synthetic line\n' * 6000
                    )
                provider = FakeProvider(
                    [NULL_PERSONAL] + [{'experiences': []}] * 20
                    if mode == 'blocks' or fallback
                    else [empty_response(), NULL_PERSONAL]
                )
                out, err = io.StringIO(), io.StringIO()
                with backend_server(backend) as url, redirect_stdout(out), redirect_stderr(err):
                    settings = config(url)
                    settings = replace(
                        settings, llm=replace(settings.llm, mode=mode, whole_timeout=987)
                    )
                    self.assertEqual(run_pull(settings, provider, once=True), 0)
                self.assertIn('accepted=1', out.getvalue())
                submitted = json.loads(backend.requests('result')[0][3])
                claimed = json.loads(backend.requests('claims')[0][3])
                self.assertEqual(submitted['provenance'], claimed['provenance'])
                self.assertEqual(
                    claimed['provenance']['promptVersion'],
                    WHOLE_PROMPT_VERSION if mode == 'whole' else PROMPT_VERSION,
                )
                self.assertNotIn('WHOLE_TOO_LONG', json.dumps(submitted))
                events = [
                    line
                    for line in err.getvalue().splitlines()
                    if 'event=worker_start ' in line or 'event=extraction_done ' in line
                ]
                self.assertEqual(len(events), 2)
                self.assertTrue(all('mode=' + mode in line for line in events))
                self.assertEqual('fallback=blocks' in err.getvalue(), fallback)
                if mode == 'whole' and not fallback:
                    self.assertEqual(
                        provider.calls, [(WHOLE_SCHEMA, 8192, 987), (PERSONAL_SCHEMA, None, None)]
                    )
                    self.assertIn('block=2/2', err.getvalue())
                for private in ('Synthetic', 'example.invalid', settings.worker_token):
                    self.assertNotIn(private, out.getvalue() + err.getvalue())

    def test_pull_config_maps_errors_to_new_env_keys(self):
        environment = {
            'CVC_LLM_BACKEND_URL': 'http://localhost',
            'CVC_LLM_WORKER_TOKEN': 'x' * 43,
            'CVC_LLM_PROVIDER': 'ollama',
            'CVC_LLM_MODEL': 'synthetic',
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'missing.env'
            for key, value, code in (
                ('CVC_LLM_MODE', 'invalid', 'INVALID_MODE'),
                ('CVC_LLM_WHOLE_TIMEOUT_S', '0', 'INVALID_WHOLE_TIMEOUT'),
            ):
                with self.assertRaisesRegex(PullConfigError, '^' + key + ' ' + code + '$'):
                    load_pull_config(
                        dict(environment, **{key: value}), path, env_file_explicit=False
                    )
            settings = load_pull_config(
                dict(environment, CVC_LLM_MODE='whole', CVC_LLM_WHOLE_TIMEOUT_S='901'),
                path,
                env_file_explicit=False,
            )
            self.assertEqual((settings.llm.mode, settings.llm.whole_timeout), ('whole', 901))

    def test_cli_mode_and_timeout_override_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.env'
            path.write_text(
                'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic\n'
                'CVC_LLM_MODE=blocks\nCVC_LLM_WHOLE_TIMEOUT_S=600\n',
                encoding='utf-8',
            )
            with (
                patch.dict(
                    os.environ,
                    {'CVC_LLM_MODE': 'blocks', 'CVC_LLM_WHOLE_TIMEOUT_S': '700'},
                    clear=True,
                ),
                patch('cv_compass_llm.cli.OllamaProvider'),
                patch(
                    'cv_compass_llm.cli.run_batch', return_value={'files': 0, 'ok': 0, 'errors': {}}
                ) as batch,
                redirect_stdout(io.StringIO()),
            ):
                self.assertEqual(
                    main(
                        [
                            'run',
                            '--input',
                            'synthetic-input',
                            '--output',
                            'synthetic-output',
                            '--env-file',
                            str(path),
                            '--mode',
                            'whole',
                            '--whole-timeout',
                            '1000',
                        ]
                    ),
                    0,
                )
            self.assertEqual(batch.call_args.kwargs['mode'], 'whole')
            self.assertEqual(batch.call_args.kwargs['whole_timeout'], 1000)

    def test_batch_summary_counts_fallbacks_and_records_mode_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, repo = root / 'input', root / 'repo'
            source.mkdir()
            repo.mkdir()
            for name, text in (
                ('a', 'Cover\nExperience\n'),
                ('b', 'Cover\nExperience\n' + 'Synthetic line\n' * 6000),
            ):
                (source / (name + '.json')).write_text(
                    json.dumps({'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': text}]}),
                    encoding='utf-8',
                )
            provider = FakeProvider(
                [empty_response(), NULL_PERSONAL, NULL_PERSONAL] + [{'experiences': []}] * 20
            )
            summary = run_batch(source, root / 'output', provider, mode='whole', repo_root=repo)
            self.assertEqual(summary['mode'], 'whole')
            self.assertEqual(summary['whole_fallbacks'], 1)
            self.assertEqual(summary['issues']['WHOLE_TOO_LONG'], 1)
            self.assertEqual(summary['prompt_version'], WHOLE_PROMPT_VERSION)
            self.assertEqual(summary['ok'], 2)
            self.assertEqual(
                json.loads((root / 'output' / 'run-summary.json').read_text()), summary
            )
