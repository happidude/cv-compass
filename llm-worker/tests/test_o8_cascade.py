"""O8 cascade acceptance with synthetic documents and in-memory providers only."""

import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from test_core import document, experience, personal
from test_pull import TOKEN, Backend, backend_server, config

from cv_compass_llm.batch_io import write_json
from cv_compass_llm.bench import run_bench
from cv_compass_llm.cli import create_provider, main, run_batch
from cv_compass_llm.config import load_bench_config, load_config, load_pull_config
from cv_compass_llm.pipeline import extract, fallback_reasons
from cv_compass_llm.providers import FakeProvider, ProviderError
from cv_compass_llm.pull import run_pull
from cv_compass_llm.schema import Issue

TEXT = 'Synthetic Person\nExperience\nRole-Z\nOrg-Z\n1/2020 - 2021-02'
NAMED = personal(fullName={'value': 'Synthetic Person', 'quote': 'Synthetic Person'})
GOOD = {'experiences': [experience()]}


def settings(**options):
    return replace(config('http://localhost').llm, **options)


class CascadeTests(unittest.TestCase):
    def test_each_quality_reason_reruns_every_call_and_uses_backup(self):
        for mode in ('blocks', 'whole'):
            for label, named, rows in (
                ('INVALID_JSON', NAMED, 'invalid'),
                ('NO_EXPERIENCE', NAMED, {'experiences': []}),
                ('NO_FULLNAME', personal(), GOOD),
                (
                    'DROP_RATIO',
                    NAMED,
                    {'experiences': [experience(), experience(title='Absent', company='Absent')]},
                ),
            ):
                with self.subTest(mode=mode, reason=label):
                    if mode == 'blocks':
                        responses = [named, rows]
                        backup_responses = [NAMED, GOOD]
                    else:
                        whole = 'invalid' if isinstance(rows, str) else NAMED | rows
                        responses = [whole, named]
                        backup_responses = [NAMED | GOOD, NAMED]
                    primary, backup = FakeProvider(responses), FakeProvider(backup_responses)
                    reasons = []
                    result = extract(
                        document(TEXT),
                        primary,
                        mode=mode,
                        config=settings(fallback_model='large'),
                        fallback_provider=backup,
                        on_fallback=reasons.append,
                    )
                    self.assertIn(label, reasons[0])
                    self.assertEqual(len(backup.calls), 2)
                    self.assertEqual(primary.prompts, backup.prompts)
                    self.assertEqual(primary.calls, backup.calls)
                    self.assertEqual(result.fields[0].key, 'fullName')
                    self.assertEqual(len(result.experiences), 1)
                    self.assertEqual(result.issues, ())

    def test_good_primary_does_not_call_backup(self):
        backup, reasons = FakeProvider([]), []
        result = extract(
            document(TEXT),
            FakeProvider([NAMED, GOOD]),
            config=settings(fallback_model='large'),
            fallback_provider=backup,
            on_fallback=reasons.append,
        )
        self.assertEqual(reasons, [()])
        self.assertEqual(backup.calls, [])
        self.assertEqual(len(result.experiences), 1)

    def test_bad_backup_is_final_and_never_retried(self):
        primary, backup = FakeProvider(['invalid'] * 2), FakeProvider(['invalid'] * 2)
        result = extract(
            document(TEXT),
            primary,
            config=settings(fallback_model='large'),
            fallback_provider=backup,
        )
        self.assertEqual(len(backup.calls), 2)
        self.assertTrue(any(issue.code == 'LLM_INVALID_JSON' for issue in result.issues))

    def test_provider_error_suppresses_all_quality_triggers(self):
        for failure in (ProviderError('OLLAMA_TIMEOUT'), ProviderError('PRIVATE')):
            backup = FakeProvider([])
            result = extract(
                document(TEXT),
                FakeProvider([failure, 'invalid']),
                config=settings(fallback_model='large'),
                fallback_provider=backup,
            )
            self.assertEqual(backup.calls, [])
            self.assertEqual(fallback_reasons(result, document(TEXT)), ())

    def test_heading_detection_is_conservative_and_ignores_failed_pages(self):
        good = extract(document(TEXT), FakeProvider([NAMED, GOOD]))
        empty = replace(good, experiences=())
        for text, expected in (
            ('Experience\n', ('NO_EXPERIENCE',)),
            ('Work Experience\n', ('NO_EXPERIENCE',)),
            ('I have experience in testing\n', ()),
            ('Projects\n', ()),
        ):
            with self.subTest(text=text):
                self.assertEqual(fallback_reasons(empty, document(text)), expected)
        doc = document('Experience')
        doc = replace(doc, pages=(replace(doc.pages[0], status='failed'),))
        self.assertEqual(fallback_reasons(empty, doc), ())

    def test_drop_ratio_counts_rejected_items_once_and_ignores_partial_loss(self):
        good = extract(document(TEXT), FakeProvider([NAMED, GOOD]))
        rejected = replace(
            good,
            issues=(
                Issue('QUOTE_NOT_FOUND', 'title'),
                Issue('QUOTE_NOT_FOUND', 'company'),
                Issue('EXPERIENCE_WITHOUT_ROLE'),
            ),
        )
        self.assertEqual(
            fallback_reasons(rejected, document(TEXT), drop_ratio=0.5), ('DROP_RATIO',)
        )
        self.assertEqual(fallback_reasons(rejected, document(TEXT), drop_ratio=0.51), ())
        partial = replace(good, issues=(Issue('QUOTE_NOT_FOUND', 'company'),))
        self.assertEqual(fallback_reasons(partial, document(TEXT)), ())
        self.assertEqual(fallback_reasons(good, document(TEXT), drop_ratio=0), ('DROP_RATIO',))
        empty = replace(good, experiences=())
        self.assertEqual(fallback_reasons(empty, document('Education'), drop_ratio=0), ())

    def test_disabled_flag_preserves_result_prompts_calls_and_callbacks(self):
        for mode in ('blocks', 'whole'):
            responses = [NAMED, GOOD] if mode == 'blocks' else [NAMED | GOOD, NAMED]
            legacy, current, backup = (
                FakeProvider(responses),
                FakeProvider(responses),
                FakeProvider([]),
            )
            before = extract(document(TEXT), legacy, mode=mode)
            after = extract(
                document(TEXT),
                current,
                mode=mode,
                config=settings(),
                fallback_provider=backup,
                on_fallback=lambda _: self.fail('disabled callback'),
            )
            self.assertEqual(before.to_dict(), after.to_dict())
            self.assertEqual(legacy.prompts, current.prompts)
            self.assertEqual(legacy.calls, current.calls)
            self.assertEqual(backup.calls, [])


class CascadeFilesTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source, self.repo = self.root / 'ocr', self.root / 'repo'
        self.source.mkdir()
        self.repo.mkdir()
        self.env_file = self.root / 'synthetic.env'
        self.env_file.write_text('CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=small\n', encoding='utf-8')

    def save_ocr(self, stem):
        write_json(
            self.source / (stem + '.json'),
            {'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': TEXT}]},
        )

    def load(self, **environ):
        return load_config({}, environ, self.env_file, env_file_explicit=True)

    def test_config_defaults_boundaries_allowlist_and_empty_override(self):
        self.assertEqual(self.load().fallback_model, '')
        self.assertEqual(self.load().fallback_drop_ratio, 0.3)
        for ratio in ('0.3', '1'):
            self.assertEqual(
                self.load(CVC_LLM_FALLBACK_DROP_RATIO=ratio).fallback_drop_ratio, float(ratio)
            )
        for ratio in ('0', '-0.1', '1.01', 'nan', 'inf', 'PRIVATE'):
            with (
                self.subTest(ratio=ratio),
                self.assertRaisesRegex(ValueError, '^INVALID_FALLBACK_DROP_RATIO$'),
            ):
                self.load(CVC_LLM_FALLBACK_DROP_RATIO=ratio)
        with self.env_file.open('a', encoding='utf-8') as stream:
            stream.write('CVC_LLM_FALLBACK_MODEL=large\nCVC_LLM_FALLBACK_DROP_RATIO=0.7\n')
        cfg = load_bench_config(self.env_file, {})
        self.assertEqual((cfg.fallback_model, cfg.fallback_drop_ratio), ('large', 0.7))
        self.assertEqual(self.load(CVC_LLM_FALLBACK_MODEL='').fallback_model, '')

    def test_combined_provenance_validates_at_startup_with_fixed_diagnostics(self):
        cfg = self.load(CVC_LLM_MODEL='qwen3:1.7b', CVC_LLM_FALLBACK_MODEL='qwen3:4b')
        self.assertEqual(cfg.provenance_model, 'qwen3:1.7b+fallback:qwen3:4b')
        self.assertRegex(cfg.provenance_model, r'^[A-Za-z0-9._:/@+-]{1,128}$')
        self.assertEqual(
            self.load(CVC_LLM_MODEL='x' * 117, CVC_LLM_FALLBACK_MODEL='y').provenance_model,
            'x' * 117 + '+fallback:y',
        )
        for primary, backup in (
            ('small', 'bad|model'),
            ('small', 'PRIVATE value'),
            ('x' * 118, 'y'),
            ('bad|model', 'large'),
        ):
            with (
                self.subTest(primary=primary, backup=backup),
                self.assertRaisesRegex(ValueError, '^INVALID_FALLBACK_MODEL$'),
            ):
                self.load(CVC_LLM_MODEL=primary, CVC_LLM_FALLBACK_MODEL=backup)
        for key, value, code in (
            ('CVC_LLM_FALLBACK_MODEL', 'bad|model', 'INVALID_FALLBACK_MODEL'),
            ('CVC_LLM_FALLBACK_DROP_RATIO', '0', 'INVALID_FALLBACK_DROP_RATIO'),
            ('CVC_LLM_FALLBACK_DROP_RATIO', 'nan', 'INVALID_FALLBACK_DROP_RATIO'),
        ):
            with self.assertRaisesRegex(ValueError, '^' + key + ' ' + code + '$'):
                load_pull_config(
                    {
                        'CVC_LLM_BACKEND_URL': 'http://localhost',
                        'CVC_LLM_WORKER_TOKEN': TOKEN,
                        key: value,
                    },
                    self.env_file,
                    env_file_explicit=True,
                )

    def test_factory_copies_all_options_and_changes_only_model_and_cascade_flag(self):
        cfg = settings(fallback_model='large', keep_alive='1m', evidence='lines', trim_input=True)
        built = []

        def factory(model, **options):
            built.append((model, options))
            return FakeProvider([])

        with patch('cv_compass_llm.cli.OllamaProvider', side_effect=factory):
            provider = create_provider(cfg)
        self.assertEqual([model for model, _ in built], [cfg.model, 'large'])
        self.assertEqual(built[0][1], built[1][1])
        self.assertEqual(
            provider.fallback_provider.extraction_config,
            replace(cfg, model='large', fallback_model=''),
        )

    def test_batch_marks_candidates_counts_fallbacks_and_includes_both_models_metrics(self):
        self.save_ocr('bad')
        self.save_ocr('good')
        cfg = settings(fallback_model='large')
        primary = FakeProvider([personal(), GOOD, NAMED, GOOD], extraction_config=cfg)
        primary.fallback_provider = FakeProvider([NAMED, GOOD])
        output = self.root / 'run'
        summary = run_batch(self.source, output, primary, repo_root=self.repo)
        self.assertEqual(summary['fallbacks'], 1)
        self.assertEqual(summary['fallback_reasons'], {'NO_FULLNAME': 1})
        self.assertEqual(summary['llm_calls'], 6)
        self.assertNotIn('selected_fallback_candidates', summary)
        self.assertEqual(
            json.loads((output / 'bad.result.json').read_text())['fallbackCandidate'],
            ['NO_FULLNAME'],
        )
        self.assertEqual(
            json.loads((output / 'good.result.json').read_text())['fallbackCandidate'], []
        )

    def test_local_candidates_without_cascade_and_cli_filters_by_complete_stem(self):
        for stem in ('a.cv', 'b', 'c'):
            self.save_ocr(stem)
        previous = self.root / 'previous'
        provider = FakeProvider([personal(), GOOD, NAMED, GOOD, NAMED, GOOD])
        run_batch(self.source, previous, provider, repo_root=self.repo)
        self.assertEqual(
            json.loads((previous / 'a.cv.result.json').read_text())['fallbackCandidate'],
            ['NO_FULLNAME'],
        )
        self.assertNotIn('fallbackCandidate', json.loads((previous / 'b.result.json').read_text()))
        before = {path: path.read_bytes() for path in [*self.source.iterdir(), *previous.iterdir()]}
        selected = FakeProvider([NAMED, GOOD])
        output = self.root / 'selected'
        with (
            patch.dict(os.environ, {}, clear=True),
            patch('cv_compass_llm.cli.create_provider', return_value=selected),
            patch('cv_compass_llm.batch_io.find_repository', return_value=self.repo),
            redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(
                main(
                    [
                        'run',
                        '--input',
                        str(self.source),
                        '--output',
                        str(output),
                        '--env-file',
                        str(self.env_file),
                        '--only-fallback-candidates',
                        str(previous),
                    ]
                ),
                0,
            )
        self.assertEqual(len(selected.calls), 2)
        self.assertEqual(
            json.loads((output / 'run-summary.json').read_text())['selected_fallback_candidates'],
            1,
        )
        self.assertEqual(
            {path.name for path in output.iterdir()}, {'a.cv.result.json', 'run-summary.json'}
        )
        self.assertEqual(before, {path: path.read_bytes() for path in before})

    def test_candidate_selection_empty_invalid_and_output_boundaries(self):
        self.save_ocr('a')
        previous = self.root / 'previous'
        previous.mkdir()
        for candidates in ([], ['PRIVATE'], 'NO_FULLNAME'):
            path = previous / 'a.result.json'
            path.write_text(json.dumps({'fallbackCandidate': candidates}), encoding='utf-8')
            provider = FakeProvider([])
            if candidates == []:
                summary = run_batch(
                    self.source,
                    self.root / 'empty',
                    provider,
                    repo_root=self.repo,
                    only_fallback_candidates=previous,
                )
                self.assertEqual(summary['files'], 0)
                self.assertEqual(summary['selected_fallback_candidates'], 0)
            else:
                with self.assertRaisesRegex(ValueError, '^INVALID_FALLBACK_CANDIDATES$'):
                    run_batch(
                        self.source,
                        self.root / 'invalid',
                        provider,
                        repo_root=self.repo,
                        only_fallback_candidates=previous,
                    )
                self.assertFalse((self.root / 'invalid').exists())
            self.assertEqual(provider.calls, [])
        with self.assertRaisesRegex(ValueError, '^UNSAFE_OUTPUT$'):
            run_batch(
                self.source,
                previous / 'nested',
                FakeProvider([]),
                repo_root=self.repo,
                only_fallback_candidates=previous,
            )

    def test_limit_applies_after_candidate_filter(self):
        previous = self.root / 'previous'
        previous.mkdir()
        for stem in ('a', 'b', 'c'):
            self.save_ocr(stem)
            write_json(
                previous / (stem + '.result.json'),
                {'fallbackCandidate': [] if stem == 'a' else ['NO_FULLNAME']},
            )
        provider = FakeProvider([NAMED, GOOD])
        output = self.root / 'limited'
        summary = run_batch(
            self.source,
            output,
            provider,
            limit=1,
            repo_root=self.repo,
            only_fallback_candidates=previous,
        )
        self.assertEqual(summary['files'], 1)
        self.assertEqual(summary['selected_fallback_candidates'], 1)
        self.assertTrue((output / 'b.result.json').is_file())
        self.assertFalse((output / 'a.result.json').exists())
        self.assertFalse((output / 'c.result.json').exists())

    def test_old_run_without_candidate_metadata_selects_zero(self):
        self.save_ocr('a')
        previous = self.root / 'old-run'
        previous.mkdir()
        write_json(previous / 'a.result.json', {'fields': [], 'experiences': []})
        provider = FakeProvider([])
        summary = run_batch(
            self.source,
            self.root / 'selected',
            provider,
            repo_root=self.repo,
            only_fallback_candidates=previous,
        )
        self.assertEqual(summary['selected_fallback_candidates'], 0)
        self.assertEqual(summary['files'], 0)
        self.assertEqual(provider.calls, [])

    def test_selection_ignores_symlink_results_and_ocr_files(self):
        previous = self.root / 'previous'
        previous.mkdir()
        for stem in ('a', 'b', 'c'):
            self.save_ocr(stem)
            write_json(previous / (stem + '.result.json'), {'fallbackCandidate': ['NO_FULLNAME']})
        result_link, ocr_link = previous / 'a.result.json', self.source / 'b.json'
        result_link.write_text('invalid unread symlink target', encoding='utf-8')
        ocr_link.write_text('invalid unread symlink target', encoding='utf-8')
        provider = FakeProvider([NAMED, GOOD])
        output = self.root / 'selected'
        # Simulate links without requiring Windows symlink privileges.
        with patch.object(Path, 'is_symlink', lambda path: path in (result_link, ocr_link)):
            summary = run_batch(
                self.source,
                output,
                provider,
                repo_root=self.repo,
                only_fallback_candidates=previous,
            )
        self.assertEqual(summary['selected_fallback_candidates'], 1)
        self.assertEqual(summary['files'], 1)
        self.assertEqual(len(provider.calls), 2)
        self.assertTrue((output / 'c.result.json').exists())
        self.assertFalse((output / 'a.result.json').exists())
        self.assertFalse((output / 'b.result.json').exists())

    def test_bench_parallel_builds_independent_backup_providers_and_counts_routes(self):
        for stem in ('a', 'b', 'c'):
            self.save_ocr(stem)
        gold = self.root / 'gold'
        gold.mkdir()
        with self.env_file.open('a', encoding='utf-8') as stream:
            stream.write('CVC_LLM_FALLBACK_MODEL=large\n')
        built = []

        def factory(cfg):
            responses = [NAMED, GOOD] if cfg.model == 'large' else [personal(), GOOD]
            provider = FakeProvider(responses * 3)
            built.append((cfg.model, provider))
            return provider

        report = run_bench(
            self.source,
            gold,
            self.root / 'bench',
            [f'cascade={self.env_file}'],
            provider_factory=factory,
            parallel=2,
            environ={},
            repo_root=self.repo,
        )
        self.assertEqual([name for name, _ in built].count('large'), 2)
        self.assertEqual(len({id(provider) for _, provider in built}), 4)
        metrics = report['configurations'][0]['metrics']
        self.assertEqual(metrics['fallbacks'], 3)
        self.assertEqual(metrics['fallback_reasons.NO_FULLNAME'], 3)
        self.assertEqual(metrics['llm_calls'], 12)


class CascadePullTests(unittest.TestCase):
    def test_provenance_is_same_for_good_and_bad_cv_and_payload_stays_unchanged(self):
        for named, expected in ((NAMED, 'no'), (personal(), 'yes')):
            backend = Backend()
            backend.input = {'pages': [{'page': 1, 'raw_text': TEXT}]}
            provider = FakeProvider([named, GOOD])
            provider.fallback_provider = FakeProvider([NAMED, GOOD])
            out, err = io.StringIO(), io.StringIO()
            with backend_server(backend) as url, redirect_stdout(out), redirect_stderr(err):
                cfg = config(url)
                cfg = replace(cfg, llm=replace(cfg.llm, fallback_model='large'))
                self.assertEqual(run_pull(cfg, provider, once=True), 0)
            claimed = json.loads(backend.requests('claims')[0][3])['provenance']
            submitted = json.loads(backend.requests('result')[0][3])
            self.assertEqual(claimed, submitted['provenance'])
            self.assertEqual(claimed['model'], 'synthetic-model+fallback:large')
            self.assertRegex(claimed['model'], r'^[A-Za-z0-9._:/@+-]{1,128}$')
            self.assertNotIn('fallbackCandidate', submitted)
            self.assertIn('cascade=' + expected, err.getvalue())
            self.assertIn(
                'cascade_reason=' + ('NO_FULLNAME' if expected == 'yes' else 'NONE'),
                err.getvalue(),
            )
            self.assertNotIn('Synthetic Person', err.getvalue() + out.getvalue())
            if expected == 'yes':
                self.assertIn('fallbacks=1', out.getvalue())
                self.assertIn('fallback_reason.NO_FULLNAME=1', out.getvalue())

    def test_whole_too_long_preserves_block_fallback_with_and_without_cascade(self):
        for enabled in (False, True):
            for named, triggered in ((NAMED, False), (personal(), True)):
                with self.subTest(cascade=enabled, candidate=triggered):
                    backend = Backend()
                    backend.input = {'pages': [{'page': 1, 'raw_text': TEXT + '\n' + 'z' * 90000}]}
                    provider = FakeProvider([named, GOOD])
                    provider.fallback_provider = FakeProvider([NAMED, GOOD])
                    out, err = io.StringIO(), io.StringIO()
                    with (
                        backend_server(backend) as url,
                        redirect_stdout(out),
                        redirect_stderr(err),
                    ):
                        cfg = config(url)
                        cfg = replace(
                            cfg,
                            llm=replace(
                                cfg.llm,
                                mode='whole',
                                fallback_model='large' if enabled else '',
                            ),
                        )
                        self.assertEqual(run_pull(cfg, provider, once=True), 0)
                    done = next(
                        line
                        for line in err.getvalue().splitlines()
                        if 'event=extraction_done ' in line
                    )
                    self.assertIn('mode=whole', done)
                    self.assertIn('fallback=blocks', done)
                    if enabled:
                        self.assertIn('cascade=' + ('yes' if triggered else 'no'), done)
                        self.assertIn(
                            'cascade_reason=' + ('NO_FULLNAME' if triggered else 'NONE'), done
                        )
                    else:
                        self.assertNotIn('cascade=', done)
                        self.assertNotIn('cascade_reason=', done)
                    self.assertEqual(len(provider.calls), 2)
                    self.assertEqual(
                        len(provider.fallback_provider.calls), 2 if enabled and triggered else 0
                    )
                    self.assertEqual(len(backend.requests('result')), 1)

    def test_primary_infrastructure_error_never_calls_backup_in_pull(self):
        backend = Backend()
        provider = FakeProvider(ProviderError('OLLAMA_TIMEOUT'))
        provider.fallback_provider = FakeProvider([])
        with (
            backend_server(backend) as url,
            redirect_stdout(io.StringIO()),
            redirect_stderr(io.StringIO()),
        ):
            cfg = config(url)
            cfg = replace(cfg, llm=replace(cfg.llm, fallback_model='large'))
            self.assertEqual(run_pull(cfg, provider, once=True), 0)
        self.assertEqual(provider.fallback_provider.calls, [])
        self.assertEqual(
            json.loads(backend.requests('failure')[0][3]), {'code': 'PROVIDER_TIMEOUT'}
        )

    def test_backup_infrastructure_error_follows_existing_failure_path(self):
        backend = Backend()
        backend.input = {'pages': [{'page': 1, 'raw_text': TEXT}]}
        provider = FakeProvider([personal(), GOOD])
        provider.fallback_provider = FakeProvider(ProviderError('OLLAMA_TIMEOUT'))
        with (
            backend_server(backend) as url,
            redirect_stdout(io.StringIO()),
            redirect_stderr(io.StringIO()),
        ):
            cfg = config(url)
            cfg = replace(cfg, llm=replace(cfg.llm, fallback_model='large'))
            self.assertEqual(run_pull(cfg, provider, once=True), 0)
        self.assertEqual(
            json.loads(backend.requests('failure')[0][3]), {'code': 'PROVIDER_TIMEOUT'}
        )
        self.assertEqual(backend.requests('result'), [])
