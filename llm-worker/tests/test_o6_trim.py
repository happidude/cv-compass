"""O6 model-input trimming with synthetic, offline source text only."""

import hashlib
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from test_core import document, experience, personal
from test_o5_lines import line_personal, position
from test_pull import config

from cv_compass_llm.config import load_bench_config, load_config, load_pull_config
from cv_compass_llm.lines import block_lines, number_lines
from cv_compass_llm.ocr_input import Document, Page
from cv_compass_llm.pipeline import extract
from cv_compass_llm.prompts import BEGIN, END, prompt_version
from cv_compass_llm.providers import FakeProvider, ProviderError
from cv_compass_llm.segmenter import Block, split_lines
from cv_compass_llm.trim import trim_spans


def settings(**options):
    return replace(config('http://localhost').llm, **options)


def data(prompt):
    return prompt.split(BEGIN + '\n', 1)[1].rsplit('\n' + END, 1)[0]


class TrimTests(unittest.TestCase):
    def test_three_page_edges_keep_first_occurrence_and_interior_repetitions(self):
        doc = document(
            'Synthetic Header\nFirst\nSecond\nInterior\nUnique\nEnd\nSynthetic Footer',
            ' synthetic\tHEADER \nThird\nFourth\nSynthetic Header\nFifth\nEnd 2\nSynthetic Footer',
            'Synthetic Header\nSixth\nSeventh\nInterior 3\nEighth\nEnd 3\nSynthetic Footer',
        )
        source = split_lines(doc.pages)
        kept, text, before, after = trim_spans(source)
        self.assertEqual(text.count('Synthetic Footer'), 1)
        self.assertEqual(text.count('Synthetic Header'), 2)
        self.assertIn(source[0], kept)
        self.assertNotIn(source[7], kept)
        self.assertIn(source[10], kept)
        self.assertIn('Unique', text)
        self.assertEqual(before, len(Block(tuple(source)).text))
        self.assertEqual(after, len(text))
        self.assertLess(after, before)
        for span in kept:
            self.assertTrue(any(span is original for original in source))
            self.assertEqual(span.raw_text[span.start : span.end], span.text)

    def test_numbers_only_at_edges_symbols_phrases_and_whitespace(self):
        for marker in ('Page 2 of 3', 'Trang 2/3', '2/3', '2 of 3', '- 2 -', '2'):
            with self.subTest(marker=marker):
                doc = document(
                    marker + '\n---\nPrivate and Confidential\n'
                    '  Engineer\t  Senior\n\n\n\n'
                    'Private and Confidentially\n' + marker,
                    'Second page body',
                    'Third page body',
                )
                _, text, _, _ = trim_spans(
                    split_lines(doc.pages), phrases=(' private AND confidential ',)
                )
                self.assertEqual(
                    text,
                    'Engineer Senior\n\nPrivate and Confidentially\nSecond page body\nThird page body',
                )
        doc = document('One\nTwo\nThree\n2\nFour\nFive\nSix')
        self.assertIn('\n2\n', trim_spans(split_lines(doc.pages))[1])
        doc = document('© example.vn\nBody\n+--+\nΩ')
        self.assertEqual(trim_spans(split_lines(doc.pages), phrases=('© EXAMPLE.VN',))[1], 'Body\nΩ')

    def test_phrase_matching_preserves_internal_whitespace_for_exact_match(self):
        doc = document('Private  and Confidential\nPrivate and Confidential')
        _, text, _, _ = trim_spans(split_lines(doc.pages), phrases=('Private and Confidential',))
        self.assertEqual(text, 'Private and Confidential')

    def test_sections_require_conservative_headings_and_resume_at_experience(self):
        raw = (
            'Preamble\nSkills in practice\nKeep prose\nskills\nKeep lowercase\n'
            'Skills\nPython\nEducation\nDegree\nExperience\nEngineer\n'
            'Projects\nJob project\nLanguages.\nKeep punctuation\n'
            'Languages\nFrench\nWork Experience\nSenior Engineer'
        )
        spans = split_lines(document(raw).pages)
        self.assertIn('Python', trim_spans(spans)[1])
        kept, text, _, _ = trim_spans(spans, sections=True)
        for expected in (
            'Preamble',
            'Skills in practice',
            'Keep prose',
            'Keep lowercase',
            'Engineer',
            'Job project',
            'Keep punctuation',
            'Senior Engineer',
        ):
            self.assertIn(expected, text)
        for removed in ('Python', 'Degree', 'French', '\nSkills\n', '\nEducation\n'):
            self.assertNotIn(removed, text)
        self.assertTrue(all(span in spans for span in kept))

    def test_empty_input_and_all_removed(self):
        self.assertEqual(trim_spans(()), ((), '', 0, 0))
        self.assertEqual(trim_spans(split_lines(document('---\n1').pages))[:2], ((), ''))

    def test_page_numbers_are_bounded_and_dates_survive_on_second_page_edges(self):
        for marker in (
            '2020',
            '09/2020',
            '12/2019',
            '2020-09',
            '4',
            '1000',
            '0',
            '2/5',
            'Page 2 of 5',
            'Trang 2/5',
            '3/2',
            '2/1000',
            '- 4 -',
        ):
            with self.subTest(marker=marker):
                doc = document(
                    'First page body',
                    marker + '\nSecond page body\n' + marker,
                    'Third page body',
                )
                kept, _, _, _ = trim_spans(split_lines(doc.pages))
                markers = [s for s in kept if s.page == 2 and s.text.strip() == marker]
                self.assertEqual(len(markers), 2)

    def test_repeated_dates_and_numbers_beyond_page_count_are_retained(self):
        for marker in ('2020', '09/2020', '12/2019', '2020-09', '4', '2/5'):
            with self.subTest(marker=marker):
                doc = document(*(marker + '\nBody ' + str(i) for i in range(3)))
                kept, _, _, _ = trim_spans(split_lines(doc.pages))
                self.assertEqual(sum(s.text.strip() == marker for s in kept), 3)

    def test_repetitions_use_nonempty_lines_and_match_the_same_page_edge(self):
        doc = document(
            '\n\n\nSynthetic Header\nFirst\nSecond\nBody 1\nEnd 1\nFooter 1\nResponsibilities:',
            'Responsibilities:\nThird\nFourth\nBody 2\nEnd 2\nFooter 2\nLast 2',
        )
        kept, text, _, _ = trim_spans(split_lines(doc.pages))
        self.assertEqual(text.count('Responsibilities:'), 2)
        self.assertEqual(sum(s.text.strip() == 'Responsibilities:' for s in kept), 2)
        for page_count in (2, 3):
            doc = document(
                *(
                    '\n\n\nShared Header\nStart '
                    + str(i)
                    + '\nMiddle '
                    + str(i)
                    + '\nEnd '
                    + str(i)
                    + '\nShared Footer\n\n\n'
                    for i in range(page_count)
                )
            )
            _, text, _, _ = trim_spans(split_lines(doc.pages))
            self.assertEqual(text.count('Shared Header'), 1)
            self.assertEqual(text.count('Shared Footer'), 1)

    def test_three_page_documents_require_three_repetitions(self):
        first = 'Shared Header\nFirst\nSecond\nBody 1\nEnd 1\nFooter 1\nShared Footer'
        second = 'Shared Header\nThird\nFourth\nBody 2\nEnd 2\nFooter 2\nShared Footer'
        doc = document(first, second, 'Third page body')
        _, text, _, _ = trim_spans(split_lines(doc.pages))
        self.assertEqual(text.count('Shared Header'), 2)
        self.assertEqual(text.count('Shared Footer'), 2)

    def test_empty_ok_pages_count_for_repetition_threshold_and_page_numbers(self):
        doc = document('Shared Header\nBody 1\n2/3', 'Shared Header\nBody 2\n2/3', '')
        _, text, _, _ = trim_spans(split_lines(doc.pages), page_count=len(doc.pages))
        self.assertEqual(text.count('Shared Header'), 2)
        self.assertNotIn('2/3', text)


class PipelineTests(unittest.TestCase):
    def test_disabled_flag_keeps_all_prompt_bytes_and_results(self):
        doc = document('Synthetic Person\nExperience\nRole-Z\t\nOrg-Z\n1/2020 - 2021-02')
        for evidence in ('quote', 'lines'):
            for mode in ('blocks', 'whole'):
                with self.subTest(evidence=evidence, mode=mode):
                    providers = [FakeProvider([{}, {}]), FakeProvider([{}, {}])]
                    before = extract(
                        doc, providers[0], mode=mode, config=settings(evidence=evidence)
                    )
                    reports = []
                    after = extract(
                        doc,
                        providers[1],
                        mode=mode,
                        config=settings(
                            evidence=evidence,
                            trim_sections=True,
                            trim_phrases=('Role-Z',),
                            trim_input=False,
                        ),
                        on_trim=lambda *counts: reports.append(counts),
                    )
                    self.assertEqual(before.to_dict(), after.to_dict())
                    self.assertEqual(providers[0].calls, providers[1].calls)
                    self.assertEqual(providers[0].prompts, providers[1].prompts)
                    self.assertEqual(
                        [hashlib.sha256(p.encode()).digest() for p in providers[0].prompts],
                        [hashlib.sha256(p.encode()).digest() for p in providers[1].prompts],
                    )
                    self.assertFalse(reports)

    def test_personal_call_is_unchanged_with_repeated_name_on_two_pages(self):
        doc = document(
            'Synthetic Person\nExperience\nEngineer\nOrg One\n2020 - 2021\nFirst footer',
            'Synthetic Person\nExperience\nSenior\nOrg Two\n2022 - 2023\nSecond footer',
        )
        for mode in ('blocks', 'whole'):
            for evidence in ('quote', 'lines'):
                with self.subTest(mode=mode, evidence=evidence):
                    baseline, trimmed = FakeProvider([{}, {}, {}]), FakeProvider([{}, {}, {}])
                    extract(doc, baseline, mode=mode, config=settings(evidence=evidence))
                    extract(
                        doc,
                        trimmed,
                        mode=mode,
                        config=settings(evidence=evidence, trim_input=True),
                    )
                    personal_index = 1 if mode == 'whole' else 0
                    self.assertEqual(
                        baseline.prompts[personal_index], trimmed.prompts[personal_index]
                    )
                    self.assertEqual(
                        data(trimmed.prompts[personal_index]).count('Synthetic Person'), 2
                    )
                    experience_index = 0 if mode == 'whole' else 1
                    self.assertEqual(
                        data(trimmed.prompts[experience_index]).count('Synthetic Person'),
                        int(mode == 'whole'),
                    )

    def test_quote_evidence_uses_retained_original_spans_in_both_modes(self):
        raw = 'Synthetic Person\n---\nExperience\nRole-Z\t  Senior\nOrg-Z\n1/2020 - 2021-02'
        doc = document(raw)
        item = experience(title='Role-Z Senior')
        for mode in ('blocks', 'whole'):
            responses = (
                [dict(personal(), experiences=[item]), personal()]
                if mode == 'whole'
                else [personal(), {'experiences': [item]}]
            )
            provider = FakeProvider(responses)
            result = extract(doc, provider, mode=mode, config=settings(trim_input=True))
            self.assertEqual(len(result.experiences), 1)
            self.assertEqual(result.experiences[0].title, 'Role-Z\t  Senior')
            evidence = result.experiences[0].evidence['title']
            self.assertEqual(raw[evidence.start : evidence.end], evidence.text)
            self.assertEqual(evidence.start, raw.index('Role-Z'))
            prompt = data(provider.prompts[0 if mode == 'whole' else 1])
            self.assertIn('Role-Z Senior', prompt)
            self.assertNotIn('---', prompt)

    def test_lines_keep_gaps_source_table_and_literal_evidence(self):
        raw = 'Synthetic Person\n---\nExperience\nEngineer\nPrivate\nSynthetic\t Works\n2020 - 2022'
        doc = document(raw)
        table = number_lines(split_lines(doc.pages))[1]
        kept, _, _, _ = trim_spans(split_lines(doc.pages), phrases=('Private',))
        rendered, selected = block_lines(Block(kept), table, trim=True)
        self.assertEqual(list(selected), [1, 3, 4, 6, 7])
        self.assertIs(selected[6], table[6])
        self.assertIn('[L6] Synthetic Works', rendered)
        self.assertNotIn('[L5]', rendered)
        for mode in ('blocks', 'whole'):
            responses = [{'experiences': [position(4, 6, 7)]}, line_personal()]
            if mode == 'blocks':
                responses.reverse()
            provider = FakeProvider(responses)
            result = extract(
                doc,
                provider,
                mode=mode,
                config=settings(evidence='lines', trim_input=True, trim_phrases=('Private',)),
            )
            self.assertEqual(len(result.experiences), 1)
            evidence = result.experiences[0].evidence['company']
            self.assertEqual(raw[evidence.start : evidence.end], 'Synthetic\t Works')
            prompt = data(provider.prompts[0 if mode == 'whole' else 1])
            self.assertIn('[L6] Synthetic Works', prompt)
            self.assertNotIn('[L5]', prompt)

    def test_removed_lines_cannot_supply_evidence(self):
        raw = 'Experience\nEngineer\nPrivate\n2020 - 2022'
        for mode in ('blocks', 'whole'):
            responses = [line_personal(), {'experiences': [position(3, 3, 4)]}]
            if mode == 'whole':
                responses.reverse()
            provider = FakeProvider(responses)
            result = extract(
                document(raw),
                provider,
                mode=mode,
                config=settings(evidence='lines', trim_input=True, trim_phrases=('Engineer',)),
            )
            self.assertFalse(result.experiences)

    def test_removed_quote_cannot_be_recovered_from_raw_text(self):
        doc = document('Experience\nRole-Z\nOrg-Z\n1/2020 - 2021-02')
        for mode in ('blocks', 'whole'):
            responses = (
                [dict(personal(), experiences=[experience()]), personal()]
                if mode == 'whole'
                else [personal(), {'experiences': [experience()]}]
            )
            result = extract(
                doc,
                FakeProvider(responses),
                mode=mode,
                config=settings(trim_input=True, trim_phrases=('Role-Z', 'Org-Z')),
            )
            self.assertFalse(result.experiences)

    def test_removed_quote_title_does_not_discard_retained_company(self):
        raw = 'Experience\nRole-Z\nOrg-Z\n1/2020 - 2021-02'
        for mode in ('blocks', 'whole'):
            responses = (
                [dict(personal(), experiences=[experience()]), personal()]
                if mode == 'whole'
                else [personal(), {'experiences': [experience()]}]
            )
            result = extract(
                document(raw),
                FakeProvider(responses),
                mode=mode,
                config=settings(trim_input=True, trim_phrases=('Role-Z',)),
            )
            self.assertEqual(len(result.experiences), 1)
            row = result.experiences[0]
            self.assertIsNone(row.title)
            self.assertNotIn('title', row.evidence)
            self.assertEqual(row.company, 'Org-Z')
            self.assertEqual((row.start, row.end), ('2020-01', '2021-02'))
            self.assertTrue(
                any(
                    issue.code == 'QUOTE_NOT_FOUND' and issue.field == 'title'
                    for issue in result.issues
                )
            )
            for evidence in row.evidence.values():
                self.assertEqual(raw[evidence.start : evidence.end], evidence.text)

    def test_two_line_period_at_top_of_second_page_keeps_year_and_raw_evidence(self):
        raw = '09/2019 -\n2020\nBuilt synthetic systems\nMore detail'
        doc = document('Cover\nExperience\nEngineer\nSynthetic Works\n', raw)
        for evidence_mode in ('quote', 'lines'):
            for mode in ('blocks', 'whole'):
                with self.subTest(evidence=evidence_mode, mode=mode):
                    if evidence_mode == 'lines':
                        record = position(3, 4, 5, period_text='09/2019 -\n2020')
                        personal_response = line_personal()
                        job_response = {'experiences': [record]}
                    else:
                        record = experience(
                            title='Engineer',
                            company='Synthetic Works',
                            start='2019-09',
                            end='2020',
                            period='09/2019 -\n2020',
                        )
                        personal_response = personal()
                        job_response = (
                            dict(personal(), experiences=[record])
                            if mode == 'whole'
                            else {'experiences': [record]}
                        )
                    responses = [personal_response, job_response]
                    if mode == 'whole':
                        responses.reverse()
                    provider = FakeProvider(responses)
                    result = extract(
                        doc,
                        provider,
                        mode=mode,
                        config=settings(evidence=evidence_mode, trim_input=True),
                    )
                    self.assertEqual(len(result.experiences), 1)
                    row = result.experiences[0]
                    self.assertEqual((row.start, row.end), ('2019-09', '2020'))
                    evidence = row.evidence['period']
                    self.assertEqual(evidence.page, 2)
                    self.assertEqual(evidence.start, 0)
                    self.assertEqual(evidence.text, '09/2019 -\n2020')
                    self.assertEqual(raw[evidence.start : evidence.end], evidence.text)
                    prompt = provider.prompts[0 if mode == 'whole' else 1]
                    self.assertIn('2020', data(prompt))

    def test_empty_trimmed_blocks_skip_provider_but_complete_progress(self):
        for evidence in ('quote', 'lines'):
            for mode in ('blocks', 'whole'):
                with self.subTest(evidence=evidence, mode=mode):
                    doc = document('Synthetic Person\nExperience\n---\nPrivate')
                    response = line_personal() if evidence == 'lines' else personal()
                    provider = FakeProvider([response])
                    progress, counts = [], []
                    result = extract(
                        doc,
                        provider,
                        mode=mode,
                        config=settings(
                            evidence=evidence,
                            trim_input=True,
                            trim_phrases=('Synthetic Person', 'Experience', 'Private'),
                        ),
                        on_trim=lambda *pair: counts.append(pair),
                        on_block=lambda index, total, seconds: progress.append((index, total)),
                    )
                    self.assertEqual(len(provider.prompts), 1)
                    self.assertIn('Synthetic Person', data(provider.prompts[0]))
                    self.assertEqual(progress, [(1, 2), (2, 2)])
                    self.assertFalse(counts)
                    self.assertFalse(result.experiences)
                    self.assertFalse(any(issue.code == 'PROVIDER_ERROR' for issue in result.issues))
                    baseline = FakeProvider([{}, {}])
                    extract(doc, baseline, mode=mode, config=settings(evidence=evidence))
                    self.assertEqual(len(baseline.prompts), 2)

    def test_whitespace_only_trimmed_block_also_skips_provider(self):
        doc = document('Experience\n \n\t\n')
        for mode in ('blocks', 'whole'):
            provider = FakeProvider([personal()])
            progress, counts = [], []
            extract(
                doc,
                provider,
                mode=mode,
                config=settings(trim_input=True, trim_phrases=('Experience',)),
                on_trim=lambda *pair: counts.append(pair),
                on_block=lambda index, total, seconds: progress.append((index, total)),
            )
            self.assertEqual(len(provider.prompts), 1)
            self.assertEqual(progress, [(1, 2), (2, 2)])
            self.assertFalse(counts)

    def test_personal_only_extraction_never_reports_trim_counts(self):
        counts = []
        provider = FakeProvider([personal()])
        extract(
            document('Synthetic Person\nExperience\n'),
            provider,
            config=settings(trim_input=True),
            on_trim=lambda *pair: counts.append(pair),
        )
        self.assertEqual(len(provider.prompts), 1)
        self.assertFalse(counts)

    def test_section_state_crosses_blocks_and_ignores_failed_pages(self):
        raw = 'Preface\nSkills\n' + 'Synthetic skill detail\n' * 1900
        doc = Document((Page(1, 'ok', raw), Page(2, 'failed', 'Private failed page')))
        for mode in ('blocks', 'whole'):
            provider = FakeProvider([{}] * 30)
            extract(
                doc,
                provider,
                mode=mode,
                config=settings(trim_input=True, trim_sections=True),
            )
            experience_prompts = provider.prompts[:1] if mode == 'whole' else provider.prompts[1:]
            for prompt in experience_prompts:
                self.assertNotIn('Synthetic skill detail', data(prompt))
            if mode == 'whole':
                self.assertEqual(data(provider.prompts[0]), 'Preface')
            else:
                self.assertEqual(len(provider.prompts), 2)
            self.assertTrue(all('Private failed page' not in p for p in provider.prompts))

    def test_whole_too_long_falls_back_with_trim_and_original_line_ids(self):
        doc = document('Preface\n' + 'Engineer\t detail\n' * 8000)
        provider = FakeProvider([{}] * 4)
        counts = []
        result = extract(
            doc,
            provider,
            mode='whole',
            max_blocks=2,
            config=settings(evidence='lines', trim_input=True),
            on_trim=lambda *pair: counts.append(pair),
        )
        self.assertTrue(any(issue.code == 'WHOLE_TOO_LONG' for issue in result.issues))
        self.assertEqual(
            result.prompt_version,
            prompt_version('whole', settings(evidence='lines', trim_input=True)),
        )
        self.assertEqual(len(provider.prompts), 3)
        self.assertEqual(len(counts), 2)
        self.assertIn('[L2] Engineer detail', data(provider.prompts[1]))
        self.assertNotIn('[L2]', data(provider.prompts[2]))

    def test_char_reports_match_actual_inputs_including_provider_failure(self):
        doc = document('Synthetic Person\nExperience\nEngineer\t  Senior\n---')
        for mode in ('blocks', 'whole'):
            provider = FakeProvider([ProviderError('SYNTHETIC'), {}])
            counts = []
            extract(
                doc,
                provider,
                mode=mode,
                config=settings(trim_input=True),
                on_trim=lambda *pair: counts.append(pair),
            )
            experience_prompts = provider.prompts[:1] if mode == 'whole' else provider.prompts[1:]
            self.assertEqual(len(counts), len(experience_prompts))
            self.assertEqual(
                [after for _, after in counts], [len(data(p)) for p in experience_prompts]
            )
            self.assertTrue(any(after < before for before, after in counts))


class ConfigTests(unittest.TestCase):
    def test_defaults_precedence_bench_and_versions(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'synthetic.env'
            path.write_text(
                'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic\n'
                'CVC_LLM_TRIM_INPUT=true\nCVC_LLM_TRIM_SECTIONS=true\n'
                'CVC_LLM_TRIM_PHRASES= Private | © example.vn ||\n',
                encoding='utf-8',
            )
            loaded = load_bench_config(path, {'CVC_LLM_TRIM_INPUT': 'false'})
            self.assertTrue(loaded.trim_input)
            self.assertTrue(loaded.trim_sections)
            self.assertEqual(loaded.trim_phrases, ('Private', '© example.vn'))
            loaded = load_config({'trim_input': 'false'}, {}, path, env_file_explicit=True)
            self.assertFalse(loaded.trim_input)
            self.assertEqual(prompt_version('blocks', loaded), prompt_version('blocks'))
        self.assertFalse(settings().trim_input)
        self.assertFalse(settings().trim_sections)
        self.assertEqual(settings().trim_phrases, ())
        for mode in ('blocks', 'whole'):
            for evidence in ('quote', 'lines'):
                base = settings(evidence=evidence)
                self.assertEqual(
                    prompt_version(mode, replace(base, trim_input=True)),
                    prompt_version(mode, base) + '-trim',
                )
                self.assertEqual(
                    prompt_version(mode, replace(base, trim_input=True, trim_sections=True)),
                    prompt_version(mode, base) + '-trims',
                )

    def test_invalid_flags_use_fixed_key_and_code(self):
        for key in ('CVC_LLM_TRIM_INPUT', 'CVC_LLM_TRIM_SECTIONS'):
            code = 'INVALID_' + key.removeprefix('CVC_LLM_')
            environment = {
                'CVC_LLM_PROVIDER': 'ollama',
                'CVC_LLM_MODEL': 'synthetic',
                'CVC_LLM_BACKEND_URL': 'http://localhost',
                'CVC_LLM_WORKER_TOKEN': 'x' * 43,
                key: 'PRIVATE_INVALID_VALUE',
            }
            with self.assertRaisesRegex(ValueError, '^' + code + '$'):
                load_config({}, environment, None, env_file_explicit=False, _file_values={})
            with tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / 'missing.env'
                with self.assertRaisesRegex(ValueError, '^' + key + ' ' + code + '$'):
                    load_pull_config(environment, path, env_file_explicit=False)
