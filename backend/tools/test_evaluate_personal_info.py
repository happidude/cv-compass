"""Synthetic acceptance tests for the offline measurement tool."""

import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from evaluate_personal_info import evaluate
from personal_info_gold import FIELDS, InputError, canonical, load


FIXTURES = Path(__file__).resolve().parents[1] / 'src/test/resources/mapping/personal-info'


def inputs() -> tuple[dict, dict]:
    return tuple(json.loads((FIXTURES / name).read_text(encoding='utf-8')) for name in (
        'synthetic-gold.json', 'synthetic-predictions.json'
    ))


class EvaluationTests(unittest.TestCase):
    def test_field_comparisons_preserve_semantics(self) -> None:
        cases = [
            ('fullName', '  Đa\u0300o   Mẫu ', 'Đào Mẫu', True),
            ('fullName', 'Đào Mẫu', 'Dao Mau', False),
            ('fullName', 'Đào Mẫu', 'Mẫu Đào', False),
            ('fullName', 'Đào Mẫu', 'đào mẫu', False),
            ('email', ' Demo@EXAMPLE.INVALID ', 'Demo@example.invalid', True),
            ('email', 'Demo@example.invalid', 'demo@example.invalid', False),
            ('location', ' Vùng   Mẫu ', 'Vùng Mẫu', True),
            ('location', 'Vùng Mẫu', 'vùng mẫu', False),
            ('summary', ' a\r\nb\r ', 'a\nb', True),
            ('summary', 'a\nb', 'a b', False),
        ]
        for field, left, right, equal in cases:
            with self.subTest(field=field, equal=equal):
                self.assertEqual(canonical(field, left) == canonical(field, right), equal)
        phone = {'number': '+99 (123) 456-789', 'extension': '02'}
        self.assertEqual(canonical('phone', phone), ('+99123456789', '02'))
        for number, extension in [('0099123456789', '02'), ('123456789', '02'),
                                  ('+99123456789', '2'), ('+99123456789', None)]:
            self.assertNotEqual(canonical('phone', phone), canonical('phone', {
                'number': number, 'extension': extension
            }))

    def test_fixture_counts_wrong_owner_and_evidence(self) -> None:
        gold, predictions = inputs()
        report = evaluate(gold, predictions)
        name = report['fields']['fullName']
        self.assertEqual((name['gold'], name['predictions'], name['tp'], name['fp'], name['fn']),
                         (2, 2, 1, 1, 1))
        self.assertEqual(name['wrong_reference'], 1)
        self.assertEqual(name['evidence_valid'], 1)
        self.assertEqual(name['evidence_invalid'], 1)
        self.assertEqual(name['precision'], 0.5)
        self.assertEqual(report['fields']['email']['missing'], 2)
        self.assertEqual(report['fields']['phone']['ambiguous'], 2)
        self.assertEqual(report['fields']['location']['invalid'], 2)
        self.assertEqual(report['fields']['summary']['disabled'], 2)
        self.assertEqual(report['fields']['summary']['recall'], 'N/A')
        self.assertIn('TABLE', report['breakdown']['layout'])
        self.assertEqual(report['breakdown']['language']['VI']['documents'], 2)
        self.assertEqual(evaluate(gold, predictions, 3)['breakdown']['layout'], {})

    def test_wrong_selection_and_abstention_count_fp_fn_once(self) -> None:
        gold, pred = inputs()
        field = pred['documents'][0]['fields']['fullName']
        for state, value, subject, expected in [
            ('PROPOSED', 'Khác Tổng Hợp', 'CANDIDATE', (0, 2, 2)),
            ('PROPOSED', 'Đào Mẫu', 'COMPANY', (0, 2, 2)),
            ('NOT_FOUND', None, 'UNKNOWN', (0, 1, 2)),
            ('AMBIGUOUS', None, 'UNKNOWN', (0, 1, 2)),
            ('INVALID', None, 'CANDIDATE', (0, 1, 2)),
        ]:
            field.update(state=state, value=value, subject=subject)
            result = evaluate(gold, pred)['fields']['fullName']
            self.assertEqual((result['tp'], result['fp'], result['fn']), expected)
        field.update(state='PROPOSED', value='Đào Mẫu', subject='COMPANY')
        self.assertEqual(evaluate(gold, pred)['fields']['fullName']['wrong_company'], 1)

    def test_empty_gold_extra_predictions_and_unresolved_annotations(self) -> None:
        gold, pred = inputs()
        name = gold['documents'][0]['fields']['fullName']
        name['acceptedValues'] = []
        result = evaluate(gold, pred)['fields']['fullName']
        self.assertEqual((result['gold'], result['tp'], result['fp'], result['fn']), (1, 0, 2, 1))
        for status in ('UNANNOTATED', 'DISPUTED'):
            name.update(status=status, acceptedValues=[])
            result = evaluate(gold, pred)['fields']['fullName']
            actual = (result['gold'], result['predictions'], result['excluded_gold'])
            self.assertEqual(actual, (1, 1, 1))
        pred['documents'][1]['fields']['fullName'].update(
            state='NOT_FOUND', value=None, subject='UNKNOWN')
        result = evaluate(gold, pred)['fields']['fullName']
        self.assertEqual(result['precision'], 'N/A')
        self.assertEqual(result['recall'], 0)

    def test_accepted_variants_alternatives_and_no_cross_document_join(self) -> None:
        gold, pred = inputs()
        gold['documents'][0]['fields']['fullName']['acceptedValues'].append('Mẫu Khác')
        pred['documents'][0]['fields']['fullName']['value'] = 'Mẫu Khác'
        pred['documents'][0]['fields']['fullName']['alternatives'] = ['Đào Mẫu', 'Mẫu Khác']
        self.assertEqual(evaluate(gold, pred)['fields']['fullName']['tp'], 1)
        pred['documents'][0]['fields']['fullName']['value'] = 'Tên Hai'
        self.assertEqual(evaluate(gold, pred)['fields']['fullName']['tp'], 0)

    def test_evidence_code_points_multiple_spans_and_missing_source(self) -> None:
        gold, pred = inputs()
        doc = gold['documents'][0]
        field = pred['documents'][0]['fields']['fullName']
        doc['source'] = {'extractionId': 'extract-a', 'pages': [{'page': 1, 'rawText': '😀Đào Mẫu'}]}
        field['evidence'] = [
            {'extractionId': 'extract-a', 'page': 1, 'start': 1, 'end': 4, 'text': 'Đào',
             'subject': 'CANDIDATE', 'supportsValue': True},
            {'extractionId': 'extract-a', 'page': 1, 'start': 5, 'end': 8, 'text': 'Mẫu',
             'subject': 'CANDIDATE', 'supportsValue': True},
        ]
        result = evaluate(gold, pred)['fields']['fullName']
        self.assertEqual(result['evidence_valid'], 2)
        for key, value in [('start', 2), ('end', 99), ('page', 2), ('text', 'fake'),
                           ('extractionId', 'other')]:
            changed = copy.deepcopy(pred)
            changed['documents'][0]['fields']['fullName']['evidence'][0][key] = value
            self.assertGreater(evaluate(gold, changed)['fields']['fullName']['evidence_invalid'], 1)
        del doc['source']
        self.assertEqual(evaluate(gold, pred)['fields']['fullName']['evidence_unverifiable'], 2)

    def test_evidence_needs_its_own_owner_and_value_support_audit(self) -> None:
        gold, pred = inputs()
        field = pred['documents'][0]['fields']['fullName']
        raw = gold['documents'][0]['source']['pages'][0]['rawText']
        start = raw.index('Synthetic Company')
        company_span = {'extractionId': 'extract-1', 'page': 1, 'start': start,
                        'end': start + len('Synthetic Company'), 'text': 'Synthetic Company',
                        'subject': 'COMPANY', 'supportsValue': True}
        field['evidence'].append(company_span)
        result = evaluate(gold, pred)['fields']['fullName']
        self.assertEqual((result['tp'], result['evidence_valid'], result['evidence_invalid']),
                         (1, 1, 2))
        for subject in ('REFERENCE', 'RECRUITER'):
            company_span['subject'] = subject
            self.assertEqual(evaluate(gold, pred)['fields']['fullName']['evidence_valid'], 1)
        company_span.update(subject='CANDIDATE', supportsValue=False)
        self.assertEqual(evaluate(gold, pred)['fields']['fullName']['evidence_invalid'], 2)
        for subject, support in [('UNKNOWN', True), ('CANDIDATE', None)]:
            company_span.update(subject=subject, supportsValue=support)
            self.assertEqual(evaluate(gold, pred)['fields']['fullName']['evidence_unverifiable'], 1)
        del company_span['subject']
        del company_span['supportsValue']
        self.assertEqual(evaluate(gold, pred)['fields']['fullName']['evidence_unverifiable'], 1)

    def test_draft_gold_never_produces_metrics(self) -> None:
        gold, pred = inputs()
        gold['confirmation'] = 'DRAFT'
        with self.assertRaises(InputError):
            evaluate(gold, pred)

    def test_empty_gold_and_abstention_do_not_create_true_positives(self) -> None:
        gold, pred = inputs()
        for doc in gold['documents']:
            doc['fields']['email']['acceptedValues'] = []
        result = evaluate(gold, pred)['fields']['email']
        self.assertEqual((result['tp'], result['fp'], result['fn']), (0, 0, 0))
        self.assertEqual((result['precision'], result['recall']), ('N/A', 'N/A'))

    def test_cli_success_only_uses_synthetic_fixture(self) -> None:
        run = subprocess.run([
            sys.executable, '-B', str(Path(__file__).with_name('evaluate_personal_info.py')),
            '--gold', str(FIXTURES / 'synthetic-gold.json'),
            '--predictions', str(FIXTURES / 'synthetic-predictions.json'),
        ], capture_output=True, text=True)
        self.assertEqual(run.returncode, 0)
        self.assertEqual(json.loads(run.stdout), evaluate(*inputs()))
        self.assertEqual(run.stderr, '')

    def test_duplicate_json_keys_are_rejected_without_values_in_errors(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'duplicate.json'
            path.write_text('{"private": 1, "private": 2}', encoding='utf-8')
            with self.assertRaisesRegex(InputError, '^EVALUATION_INPUT_REJECTED$'):
                load(path)

    def test_invalid_email_alternatives_and_bad_selected_value_still_measure(self) -> None:
        gold, pred = inputs()
        field = pred['documents'][0]['fields']['email']
        field.update(state='INVALID', alternatives=['OCR at example', 'O0-I1'])
        self.assertEqual(evaluate(gold, pred)['fields']['email']['invalid'], 1)
        field.update(state='PROPOSED', value='OCR at example', subject='CANDIDATE')
        result = evaluate(gold, pred)['fields']['email']
        self.assertEqual((result['tp'], result['fp'], result['fn']), (0, 1, 2))

    def test_rejects_malformed_join_labels_states_and_disabled_field(self) -> None:
        gold, pred = inputs()
        mutations = [
            lambda g, p: p['documents'].pop(),
            lambda g, p: p['documents'].append(copy.deepcopy(p['documents'][0])),
            lambda g, p: g['documents'][0].update(layout='private value'),
            lambda g, p: p['documents'][0]['fields']['email'].update(state='UNKNOWN'),
            lambda g, p: p['documents'][0]['fields']['email'].update(value='not absent'),
            lambda g, p: p['documents'][0]['fields']['summary'].update(issues=[]),
            lambda g, p: p['documents'][0]['fields'].pop('phone'),
            lambda g, p: g.update(schemaVersion=True),
            lambda g, p: g['documents'][0]['fields']['email'].update(status='TYPO'),
            lambda g, p: p['documents'][0]['fields']['fullName']['evidence'][0].update(start=True),
        ]
        for mutate in mutations:
            with self.subTest(index=mutations.index(mutate)):
                g, p = copy.deepcopy(gold), copy.deepcopy(pred)
                mutate(g, p)
                with self.assertRaises(InputError):
                    evaluate(g, p)
        with self.assertRaises(InputError):
            evaluate(gold, pred, 1)

    def test_report_and_cli_errors_never_echo_values_paths_or_ids(self) -> None:
        gold, pred = inputs()
        sentinel = 'PRIVATE_SENTINEL_DO_NOT_EMIT'
        for doc in gold['documents'] + pred['documents']:
            doc['documentKey'] = sentinel + doc['documentKey']
        gold['documents'][0]['fields']['fullName']['acceptedValues'] = [sentinel]
        report = json.dumps(evaluate(gold, pred))
        self.assertNotIn(sentinel, report)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / (sentinel + '.json')
            path.write_text('{"' + sentinel + '"', encoding='utf-8')
            run = subprocess.run([sys.executable, '-B', str(Path(__file__).with_name(
                'evaluate_personal_info.py')), '--gold', str(path), '--predictions', str(path)],
                capture_output=True, text=True)
            self.assertEqual(run.returncode, 2)
            self.assertEqual(run.stderr.strip(), 'EVALUATION_INPUT_REJECTED')
            self.assertNotIn(sentinel, run.stdout + run.stderr)
        for arguments in [['--min-group-size', sentinel], ['--unknown', sentinel]]:
            run = subprocess.run([
                sys.executable, '-B', str(Path(__file__).with_name('evaluate_personal_info.py')),
                '--gold', sentinel, '--predictions', sentinel, *arguments,
            ], capture_output=True, text=True)
            self.assertEqual(run.returncode, 2)
            self.assertEqual(run.stderr.strip(), 'EVALUATION_INPUT_REJECTED')
            self.assertNotIn(sentinel, run.stdout + run.stderr)

    def test_every_field_is_reported(self) -> None:
        self.assertEqual(set(evaluate(*inputs())['fields']), set(FIELDS))

    def test_breakdowns_are_separate_allowlisted_dimensions(self) -> None:
        gold, pred = inputs()
        for doc in gold['documents']:
            doc.update(layout='MULTI_COLUMN', language='EN')
        report = evaluate(gold, pred)
        self.assertEqual(set(report['breakdown']['layout']), {'MULTI_COLUMN'})
        self.assertEqual(set(report['breakdown']['language']), {'EN'})
        self.assertEqual(report['breakdown']['layout']['MULTI_COLUMN']['fields'], report['fields'])

    def test_disabled_and_unresolved_support_are_disclosed_separately(self) -> None:
        gold, pred = inputs()
        gold['documents'][0]['fields']['summary']['status'] = 'UNANNOTATED'
        gold['documents'][0]['fields']['summary']['acceptedValues'] = []
        result = evaluate(gold, pred)['fields']['summary']
        self.assertEqual((result['documents'], result['enabled'], result['disabled'],
                          result['excluded_gold'], result['evaluated']), (2, 0, 2, 1, 0))


if __name__ == '__main__':
    unittest.main()
