"""M6.1 contract/reference checks only; no PDF/OCR pipeline or production config."""
from __future__ import annotations
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parent
LIMITS = json.loads((ROOT / 'ocr-v2.limits.json').read_text(encoding='utf-8'))
SCHEMA = json.loads((ROOT / 'ocr-v2.schema.json').read_text(encoding='utf-8'))


def effective_limits(config: dict) -> dict:
    """Reference resolver: one manifest defines defaults and contract ceilings."""
    ceilings = {k: LIMITS[k] for k in ('max_pages', 'max_text_code_points_per_field')}
    if set(config) - set(ceilings):
        raise ValueError('INVALID_CONFIGURATION')
    result = ceilings | config
    if any(type(v) is not int or not 1 <= v <= ceilings[k] for k, v in result.items()):
        raise ValueError('INVALID_CONFIGURATION')
    return result


def before_pdf(config: dict, open_pdf):
    limits = effective_limits(config)
    return open_pdf(limits)


def classify(pages: list[dict], errors: list[dict]) -> str:
    if not any(p['status'] == 'ok' for p in pages):
        return 'failed'
    return 'partial' if errors or any(p['status'] in ('failed', 'not_processed') for p in pages) else 'success'


def semantic_check(result: dict, config: dict | None = None) -> None:
    limits = effective_limits(config or {})
    pages = result['pages']
    count = result['page_count']
    if count is None:
        if pages:
            raise ValueError('UNKNOWN_PAGE_COUNT')
    elif type(count) is not int or not 1 <= count <= limits['max_pages'] or len(pages) != count:
        raise ValueError('PAGE_COUNT_LIMIT')
    for index, page in enumerate(pages, 1):
        if page['page_number'] != index:
            raise ValueError('PAGE_ORDER')
        if len(page['raw_text']) > limits['max_text_code_points_per_field']:
            raise ValueError('OUTPUT_LIMIT')
        if page['status'] == 'ok' and (not page['raw_text'].strip() or page['error'] is not None):
            raise ValueError('PAGE_STATUS')
        if page['status'] in ('failed', 'not_processed') and (page['error'] is None):
            raise ValueError('PAGE_STATUS')
        if page['status'] == 'blank' and (page['raw_text'] or page['error'] is not None):
            raise ValueError('PAGE_STATUS')
    if result['status'] != classify(pages, result['errors']):
        raise ValueError('DOCUMENT_STATUS')


def serialize_if_fits(result: dict, byte_cap: int) -> bytes:
    """Reference publication boundary; no truncation or filesystem side effects."""
    data = json.dumps(result, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if len(data) > byte_cap:
        raise ValueError('EXIT_3_NO_DELIVERY')
    return data


def example(name: str) -> dict:
    return json.loads((ROOT / 'examples-v2' / name).read_text(encoding='utf-8'))


class ContractChecks(unittest.TestCase):
    def test_canonical_v2_ceiling_and_schema_projections(self):
        # Frozen compatibility guard: raising v2 caps requires contract review.
        self.assertEqual(LIMITS, {'schema_version': 'cv-compass.ocr/2.0', 'max_pages': 50,
                                  'max_text_code_points_per_field': 50000})
        r, p = SCHEMA['$defs']['result'], SCHEMA['$defs']['page']
        for value in (r['properties']['pages']['maxItems'], r['properties']['page_count']['maximum'],
                      p['properties']['page_number']['maximum']):
            self.assertEqual(value, LIMITS['max_pages'])
        self.assertEqual(p['properties']['raw_text']['maxLength'], LIMITS['max_text_code_points_per_field'])
        self.assertNotIn('text', p['properties'])
        self.assertEqual(effective_limits({}), {k: LIMITS[k] for k in ('max_pages', 'max_text_code_points_per_field')})

    def test_config_lower_or_equal(self):
        for pages, text in ((1, 1), (10, 1000), (50, 50000)):
            with self.subTest(pages=pages, text=text):
                self.assertEqual(effective_limits({'max_pages': pages, 'max_text_code_points_per_field': text}),
                                 {'max_pages': pages, 'max_text_code_points_per_field': text})

    def test_invalid_config_rejected_before_pdf(self):
        for config in ({'max_pages': 51}, {'max_text_code_points_per_field': 50001},
                       {'max_pages': 0}, {'max_pages': -1}, {'max_pages': True},
                       {'max_pages': 10.0}, {'max_text_code_points_per_field': 0}, {'unknown': 1}):
            with self.subTest(config=config):
                opener = Mock()
                with self.assertRaisesRegex(ValueError, 'INVALID_CONFIGURATION'):
                    before_pdf(config, opener)
                opener.assert_not_called()

    def test_every_result_example_semantics(self):
        for path in sorted((ROOT / 'examples-v2').glob('result-*.json')):
            with self.subTest(example=path.name):
                semantic_check(example(path.name))

    def test_status_decision_matrix_preserves_pages(self):
        base = example('result-success.json')
        good, blank, bad, pending = ({'status': s} for s in ('ok', 'blank', 'failed', 'not_processed'))
        for pages, errors, status in (([good], [], 'success'), ([good, blank], [], 'success'),
                                     ([good, bad], [], 'partial'), ([good, pending], [], 'partial'),
                                     ([good], [{'code': 'OUTPUT_LIMIT'}], 'partial'),
                                     ([blank], [], 'failed'), ([bad], [], 'failed'), ([], [], 'failed')):
            with self.subTest(expected=status, pages=pages):
                before = copy.deepcopy(pages)
                self.assertEqual(classify(pages, errors), status)
                self.assertEqual(pages, before)
        doc_error = example('result-partial-document-error.json')
        self.assertEqual(doc_error['pages'], base['pages'])
        self.assertTrue(all(p['status'] == 'ok' for p in doc_error['pages']))
        output = example('result-output-limit.json')
        self.assertEqual(output['pages'][:2], example('result-mixed.json')['pages'][:2])
        self.assertEqual(output['pages'][2]['error']['code'], 'OUTPUT_LIMIT')

    def test_cleanup_warning_does_not_change_success(self):
        result = example('result-success.json')
        before = copy.deepcopy(result['pages'])
        result['warnings'].append('CLEANUP_FAILED')
        semantic_check(result)
        self.assertEqual(result['status'], 'success')
        self.assertEqual(before, result['pages'])

    def test_semantics_reject_wrong_status_and_order(self):
        for mutation in ('all_ok_partial_without_error', 'failed_with_ok', 'duplicate_page'):
            result = example('result-success.json')
            if mutation == 'all_ok_partial_without_error': result['status'] = 'partial'
            if mutation == 'failed_with_ok': result['status'] = 'failed'
            if mutation == 'duplicate_page': result['pages'][1]['page_number'] = 1
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                semantic_check(result)

    def test_unicode_code_points_and_effective_limits(self):
        result = example('result-success.json')
        result['pages'][0]['raw_text'] = '\U0001f600' * 50000
        semantic_check(result)  # 50k code points, 100k UTF-16 units, 200k UTF-8 bytes.
        result['pages'][0]['raw_text'] += '\U0001f600'
        with self.assertRaisesRegex(ValueError, 'OUTPUT_LIMIT'):
            semantic_check(result)
        result = example('result-success.json')
        result['pages'][0]['raw_text'] = 'a\u0301' * 25000
        semantic_check(result)  # 25k graphemes but 50k code points in raw_text.
        result['pages'][0]['raw_text'] += 'x'
        with self.assertRaisesRegex(ValueError, 'OUTPUT_LIMIT'):
            semantic_check(result)
        with self.assertRaisesRegex(ValueError, 'OUTPUT_LIMIT'):
            semantic_check(example('result-success.json'), {'max_text_code_points_per_field': 2})
        with self.assertRaisesRegex(ValueError, 'PAGE_COUNT_LIMIT'):
            semantic_check(example('result-success.json'), {'max_pages': 1})

    def test_no_truncated_json_delivery(self):
        result = example('result-output-limit.json')
        original = copy.deepcopy(result)
        data = serialize_if_fits(result, 8388608)
        self.assertEqual(json.loads(data), result)
        self.assertEqual(serialize_if_fits(result, len(data)), data)
        with self.assertRaisesRegex(ValueError, 'EXIT_3_NO_DELIVERY'):
            serialize_if_fits(result, len(data) - 1)
        self.assertEqual(result, original)

    def test_isolation_metadata_not_part_of_ocr_contract(self):
        result_props = SCHEMA['$defs']['result']['properties']
        for name in ('isolation', 'requested_limits', 'applied_limits', 'enforced', 'not_enforced'):
            self.assertNotIn(name, result_props)
        self.assertFalse(SCHEMA['$defs']['result']['additionalProperties'])


    def test_json_schema_and_all_examples(self):
        from jsonschema import Draft202012Validator
        Draft202012Validator.check_schema(SCHEMA)
        validator = Draft202012Validator(SCHEMA)
        for path in sorted((ROOT / 'examples-v2').glob('*.json')):
            with self.subTest(example=path.name):
                validator.validate(json.loads(path.read_text(encoding='utf-8')))

    def test_schema_status_and_privacy_negatives(self):
        from jsonschema import Draft202012Validator
        v = Draft202012Validator(SCHEMA)
        mutations = []
        x = example('request.json'); x['input_ref'] = '../outside.pdf'; mutations.append(x)
        x = example('request.json'); x['schema_version'] = 'unknown'; mutations.append(x)
        x = example('result-success.json'); x['status'] = 'partial'; mutations.append(x)
        x = example('result-success.json'); x['status'] = 'failed'; mutations.append(x)
        x = example('result-success.json'); x['isolation'] = {'status': 'enforced'}; mutations.append(x)
        x = example('result-success.json'); x['errors'] = [{'code': 'OUTPUT_LIMIT', 'stage': 'output'}]; mutations.append(x)
        x = example('result-mixed.json'); x['pages'][1]['confidence']['value'] = 101; mutations.append(x)
        x = example('result-mixed.json'); x['pages'][2]['text'] = 'removed v2 field'; mutations.append(x)
        for index, candidate in enumerate(mutations):
            with self.subTest(case=index):
                self.assertTrue(list(v.iter_errors(candidate)))

    def test_schema_unicode_and_page_boundaries(self):
        from jsonschema import Draft202012Validator
        v = Draft202012Validator(SCHEMA)
        result = example('result-success.json')
        result['pages'][0]['raw_text'] = '\U0001f600' * 50000
        v.validate(result)
        result['pages'][0]['raw_text'] += '\U0001f600'
        self.assertTrue(list(v.iter_errors(result)))
        result = example('result-success.json')
        page = result['pages'][0]
        result['pages'] = [dict(copy.deepcopy(page), page_number=i) for i in range(1, 51)]
        result['page_count'] = 50
        v.validate(result)
        result['pages'].append(dict(copy.deepcopy(page), page_number=51))
        result['page_count'] = 51
        self.assertTrue(list(v.iter_errors(result)))

    def test_failed_is_defined_by_no_ok_not_document_error_presence(self):
        from jsonschema import Draft202012Validator
        source = example('result-mixed.json')
        source['pages'] = [source['pages'][2]]
        source['pages'][0]['page_number'] = 1
        source.update(status='failed', page_count=1, errors=[])
        semantic_check(source)
        Draft202012Validator(SCHEMA).validate(source)
        blank = example('result-blank.json')
        blank['errors'] = []
        semantic_check(blank)
        Draft202012Validator(SCHEMA).validate(blank)

if __name__ == '__main__':
    unittest.main(verbosity=2)
