"""Synthetic normalization contract, privacy and lease regression tests."""

import io
import json
import tempfile
import time
import unicodedata
import unittest
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import Mock, patch

from test_pull import TOKEN, backend_server, config, instant

from cv_compass_llm.backend_client import NORM_PREFIX, BackendClient, BackendError, encode
from cv_compass_llm.cli import main
from cv_compass_llm.config import (
    NORM_BATCH_KEY,
    NORM_MAX_BODY_BYTES,
    NormConfig,
    PullConfigError,
    _read_env,
    load_norm_config,
)
from cv_compass_llm.norm_prompts import PROMPT_VERSION, SCHEMA, build_prompt
from cv_compass_llm.normalize import bound_results, normalize_batch, parse_results, run_normalize
from cv_compass_llm.providers.base import ProviderError

TAXONOMY = {
    'FUNCTION': [
        {'code': 'SALES', 'label': 'Kinh doanh'},
        {'code': 'HR', 'label': 'Nhân sự'},
        {'code': 'OTHER', 'label': 'Khác'},
    ],
    'SENIORITY': [
        {'code': 'MANAGER', 'label': 'Quản lý'},
        {'code': 'EXECUTIVE', 'label': 'Điều hành'},
    ],
    'INDUSTRY': [{'code': 'RETAIL', 'label': 'Bán lẻ'}, {'code': 'OTHER', 'label': 'Khác'}],
    'SCOPE': [{'code': 'COMPANY', 'label': 'Toàn công ty'}, {'code': 'REGION', 'label': 'Khu vực'}],
}
ITEMS = [
    {'id': 1, 'kind': 'COMPANY', 'raw': 'Mây Lam Ltd (Sao Tím Group)'},
    {'id': 2, 'kind': 'TITLE', 'raw': 'Regional Sales Manager', 'contextCompany': 'Mây Lam'},
]
RESULTS = [
    {
        'id': 1,
        'company': {
            'name': 'Mây Lam',
            'parent': 'Sao Tím',
            'industry': 'RETAIL',
            'proposedLabel': None,
        },
    },
    {
        'id': 2,
        'title': {
            'functions': ['SALES'],
            'seniority': 'MANAGER',
            'scope': 'REGION',
            'proposedLabel': None,
        },
    },
]


def response(results=RESULTS):
    return json.dumps({'results': results}, ensure_ascii=False)


class NormBackend:
    def __init__(self):
        self.calls = []
        self.claim = {
            'token': 'a0000000-0000-4000-8000-000000000001',
            'leaseExpiresAt': instant(120),
            'taxonomyVersion': 'cv-taxonomy/0',
            'taxonomy': TAXONOMY,
            'items': ITEMS,
        }
        self.hook = None

    def reply(self, method, path, headers, body):
        self.calls.append((method, path, dict(headers), body))
        if self.hook:
            override = self.hook(method, path, headers, body)
            if override is not None:
                return override
        if path.endswith('/claims'):
            return 200, self.claim
        return 200, {'accepted': 2, 'rejected': []}


class NormalizationValidationTests(unittest.TestCase):
    def setUp(self):
        self.results = json.loads(response())['results']
        self.llm = config('http://127.0.0.1').llm

    def parse(self):
        return parse_results(response(self.results), ITEMS, TAXONOMY)

    def test_prompt_has_only_allowlisted_items_and_claim_tables(self):
        source = [
            row | {'cvText': 'FORBIDDEN_CV_TEXT', 'email': 'FORBIDDEN_CONTACT'} for row in ITEMS
        ]
        prompt = build_prompt(source, TAXONOMY)
        self.assertEqual(PROMPT_VERSION, 'norm-1')
        self.assertNotIn('FORBIDDEN', prompt)
        self.assertEqual(json.loads(prompt.split('ITEMS=')[1]), ITEMS)
        self.assertEqual(json.loads(prompt.split('TAXONOMY=')[1].split('\nITEMS=')[0]), TAXONOMY)
        for rule in ('Never translate', 'EXECUTIVE', 'BUSINESS_UNIT', 'OTHER', 'Vietnamese'):
            self.assertIn(rule, prompt)

    def test_valid_batch_uses_one_provider_call(self):
        provider = Mock()
        provider.generate.return_value = response()
        self.assertEqual(normalize_batch(ITEMS, TAXONOMY, provider, self.llm), RESULTS)
        provider.generate.assert_called_once_with(
            build_prompt(ITEMS, TAXONOMY), SCHEMA, num_ctx=None, timeout_s=2
        )

    def test_extra_keys_or_invalid_json_reject_entire_batch(self):
        extra = json.loads(response())
        extra['results'][0]['company']['inference'] = 'FORBIDDEN'
        for raw in (
            'not json',
            response() + '{}',
            '{"results":[],"results":[]}',
            json.dumps(extra),
            json.dumps({'results': RESULTS, 'extra': 1}),
            response([RESULTS[0] | {'extra': None}, RESULTS[1]]),
        ):
            with self.subTest(raw_index=len(raw)):
                self.assertEqual(
                    parse_results(raw, ITEMS, TAXONOMY),
                    [{'id': item['id'], 'error': 'LLM_INVALID_JSON'} for item in ITEMS],
                )

    def test_semantic_failures_reject_only_the_item(self):
        for field, changes, expected in (
            ('company', {'industry': 'INVENTED'}, 'INVALID_CODE'),
            ('company', {'industry': 'OTHER', 'proposedLabel': '  '}, 'PROPOSED_LABEL_REQUIRED'),
            ('company', {'name': 'Mây Lam Expanded'}, 'NAME_NOT_IN_SOURCE'),
            ('company', {'name': ''}, 'NAME_NOT_IN_SOURCE'),
            ('company', {'parent': 'Invented Parent'}, 'PARENT_NOT_IN_SOURCE'),
            ('title', {'functions': ['SALES', 'HR', 'OTHER']}, 'LLM_INVALID_JSON'),
            ('title', {'functions': ['SALES', 'SALES']}, 'LLM_INVALID_JSON'),
            ('title', {'functions': []}, 'LLM_INVALID_JSON'),
            ('title', {'functions': ['INVENTED']}, 'INVALID_CODE'),
            ('title', {'seniority': 'INVENTED'}, 'INVALID_CODE'),
            ('title', {'scope': 'INVENTED'}, 'INVALID_CODE'),
            ('title', {'functions': ['OTHER']}, 'PROPOSED_LABEL_REQUIRED'),
        ):
            with self.subTest(field=field, code=expected):
                self.results = json.loads(response())['results']
                index = field == 'title'
                self.results[index][field].update(changes)
                actual = self.parse()
                self.assertEqual(actual[index], {'id': ITEMS[index]['id'], 'error': expected})
                self.assertEqual(actual[not index], RESULTS[not index])

    def test_missing_wrong_kind_unknown_and_duplicate_ids(self):
        self.assertEqual(
            parse_results(response([RESULTS[0]]), ITEMS, TAXONOMY)[1],
            {'id': 2, 'error': 'MISSING_RESULT'},
        )
        wrong = [RESULTS[1] | {'id': 1}, RESULTS[0] | {'id': 2}]
        self.assertEqual(
            parse_results(response(wrong), ITEMS, TAXONOMY),
            [{'id': item['id'], 'error': 'LLM_INVALID_JSON'} for item in ITEMS],
        )
        for invalid in (
            [RESULTS[0], RESULTS[0]],
            [RESULTS[0] | {'id': 3}],
            [RESULTS[0] | {'id': True}],
        ):
            self.assertTrue(
                all(
                    row['error'] == 'LLM_INVALID_JSON'
                    for row in parse_results(response(invalid), ITEMS, TAXONOMY)
                )
            )

    def test_substring_whitespace_and_other_label(self):
        self.results[0]['company'].update(name='Mây   Lam', parent='Sao  Tím')
        self.results[1]['title'].update(functions=['OTHER'], proposedLabel='Nghề tổng hợp')
        self.assertEqual(self.parse(), self.results)
        self.results[0]['company']['name'] = 'May Lam'
        self.assertEqual(self.parse()[0]['error'], 'NAME_NOT_IN_SOURCE')

    def test_case_changes_are_rejected_for_name_and_parent(self):
        self.results[0]['company']['name'] = 'mây Lam'
        self.assertEqual(self.parse()[0]['error'], 'NAME_NOT_IN_SOURCE')
        self.results[0]['company'].update(name='Mây Lam', parent='sao Tím')
        self.assertEqual(self.parse()[0]['error'], 'PARENT_NOT_IN_SOURCE')

    def test_nfd_source_accepts_nfc_name_and_parent(self):
        items = [row | {'raw': unicodedata.normalize('NFD', row['raw'])} for row in ITEMS]
        self.assertEqual(parse_results(response(), items, TAXONOMY), RESULTS)

    def test_parent_same_as_name_after_backend_fold_is_rejected(self):
        for name, parent, raw in (
            ('Mây Lam', 'Mây Lam', 'Mây Lam Ltd'),
            ('Mây Lam', 'MAY LAM', 'Mây Lam (MAY LAM Group)'),
            ('Đốm Lam', 'dom lam', 'Đốm Lam (dom lam Group)'),
        ):
            with self.subTest(parent_length=len(parent)):
                rows = [
                    RESULTS[0]
                    | {
                        'company': RESULTS[0]['company']
                        | {
                            'name': name,
                            'parent': parent,
                        }
                    }
                ]
                items = [ITEMS[0] | {'raw': raw}]
                self.assertEqual(
                    parse_results(response(rows), items, TAXONOMY),
                    [
                        {'id': 1, 'error': 'PARENT_SAME_AS_NAME'},
                    ],
                )

    def test_generated_string_limits_apply_per_item_in_code_points(self):
        for index, kind, field in (
            (0, 'company', 'name'),
            (0, 'company', 'parent'),
            (0, 'company', 'proposedLabel'),
            (1, 'title', 'proposedLabel'),
        ):
            for length in (255, 256):
                with self.subTest(field=field, kind=kind, length=length):
                    rows = json.loads(response())['results']
                    items = [dict(item) for item in ITEMS]
                    value = '🧪' * length
                    rows[index][kind][field] = value
                    if field in ('name', 'parent'):
                        items[index]['raw'] += ' ' + value
                    parsed = parse_results(response(rows), items, TAXONOMY)
                    if length == 255:
                        self.assertEqual(parsed[index], rows[index])
                    else:
                        self.assertEqual(
                            parsed[index], {'id': items[index]['id'], 'error': 'LLM_FIELD_TOO_LONG'}
                        )
                    self.assertEqual(parsed[not index], rows[not index])

    def test_body_limit_uses_encoded_bytes_and_replaces_longest_items_first(self):
        token = 'a0000000-0000-4000-8000-000000000001'
        rows = [
            {
                'id': index,
                'company': {
                    'name': '🧪' * 255,
                    'parent': '🪁' * 255,
                    'industry': 'RETAIL',
                    'proposedLabel': ('🧭' * 255 if index != 50 else None),
                },
            }
            for index in range(1, 51)
        ]
        before = encode({'token': token, 'results': rows})
        self.assertGreater(len(before), NORM_MAX_BODY_BYTES)
        bounded = bound_results(token, rows)
        self.assertLessEqual(len(encode({'token': token, 'results': bounded})), NORM_MAX_BODY_BYTES)
        self.assertEqual(len(bounded), 50)
        self.assertEqual([row['id'] for row in bounded], list(range(1, 51)))
        self.assertTrue(any(row.get('error') == 'RESULT_TOO_LARGE' for row in bounded))
        self.assertEqual(bounded[-1], rows[-1])
        self.assertTrue(all('company' in row for row in rows))
        removed = next(index for index, row in enumerate(bounded) if 'error' in row)
        restored = [row if index != removed else rows[index] for index, row in enumerate(bounded)]
        self.assertGreater(len(encode({'token': token, 'results': restored})), NORM_MAX_BODY_BYTES)

    def test_claim_taxonomy_is_authoritative(self):
        taxonomy = json.loads(json.dumps(TAXONOMY))
        taxonomy['INDUSTRY'] = [{'code': 'OTHER', 'label': 'Khác'}]
        self.assertEqual(parse_results(response(), ITEMS, taxonomy)[0]['error'], 'INVALID_CODE')

    def test_guard_splits_only_when_needed_and_never_more_than_two(self):
        items = [ITEMS[0] | {'id': i, 'raw': 'Mây Lam ' + 'x' * 15000} for i in (1, 2)]
        half_size = len(build_prompt(items[:1], TAXONOMY))
        llm = replace(
            self.llm,
            ctx_guard=True,
            ctx_chars_per_token=1,
            max_output_tokens=10,
            num_ctx=half_size + 11,
        )
        provider = Mock()
        provider.generate.side_effect = [response([RESULTS[0]]), response([RESULTS[0] | {'id': 2}])]
        results = normalize_batch(items, TAXONOMY, provider, llm)
        self.assertEqual(len(results), 2)
        self.assertEqual(provider.generate.call_count, 2)
        provider.reset_mock()
        with self.assertRaises(ProviderError):
            normalize_batch(items, TAXONOMY, provider, replace(llm, num_ctx=1))
        provider.generate.assert_not_called()

    def test_bad_claim_never_calls_provider(self):
        provider = Mock()
        for items, taxonomy in ((ITEMS * 2, TAXONOMY), (ITEMS, {}), ([], TAXONOMY)):
            with self.assertRaises(ValueError):
                normalize_batch(items, taxonomy, provider, self.llm)
        provider.generate.assert_not_called()


class NormalizationTransportTests(unittest.TestCase):
    def run_worker(self, backend, provider=None):
        provider = provider or Mock()
        provider.generate.return_value = response()
        output, logs = io.StringIO(), io.StringIO()
        with backend_server(backend) as url, redirect_stdout(output), redirect_stderr(logs):
            result = run_normalize(NormConfig(config(url)), provider, once=True)
        return result, output.getvalue(), logs.getvalue(), provider

    def test_exact_submit_payload_bearer_and_no_sensitive_logs(self):
        backend = NormBackend()
        result, output, logs, provider = self.run_worker(backend)
        self.assertEqual(result, 0)
        self.assertEqual(len(backend.calls), 2)
        claim_call, submit_call = backend.calls
        self.assertEqual(claim_call[:2], ('POST', NORM_PREFIX + '/claims'))
        self.assertEqual(claim_call[3], b'{"max":50}')
        self.assertEqual(submit_call[:2], ('PUT', NORM_PREFIX + '/results'))
        self.assertEqual(
            submit_call[3], encode({'token': backend.claim['token'], 'results': RESULTS})
        )
        for call in backend.calls:
            self.assertEqual(call[2]['Authorization'], 'Bearer ' + TOKEN)
            self.assertNotIn('X-Lease-Token', call[2])
        self.assertEqual(provider.generate.call_count, 1)
        self.assertIn('accepted=2', output)
        for forbidden in (
            TOKEN,
            backend.claim['token'],
            *(item['raw'] for item in ITEMS),
            'Mây Lam',
            'Sao Tím',
            'SCHEMA=',
            'TAXONOMY=',
        ):
            self.assertNotIn(forbidden, output + logs)

    def test_idle_204_is_success_without_provider(self):
        backend = NormBackend()
        backend.hook = lambda *_: (204, {})
        result, output, _, provider = self.run_worker(backend)
        self.assertEqual(result, 0)
        self.assertIn('idle=1', output)
        provider.generate.assert_not_called()

    def test_lease_lost_is_counted_without_retry_or_error_exit(self):
        backend = NormBackend()
        backend.hook = lambda method, *_: (409, {'code': 'LEASE_LOST'}) if method == 'PUT' else None
        result, output, _, _ = self.run_worker(backend)
        self.assertEqual(result, 0)
        self.assertIn('lease_lost=1', output)
        self.assertEqual(len(backend.calls), 2)

    def test_submit_503_retries_exact_bytes_beyond_attempt_limit(self):
        backend = NormBackend()
        failures = 0

        def hook(method, *_):
            nonlocal failures
            if method == 'PUT':
                failures += 1
                if failures <= 4:
                    return 503, {'code': 'NORMALIZATION_UNAVAILABLE'}

        backend.hook = hook
        with patch('cv_compass_llm.backend_client.time.sleep'):
            result, _, _, _ = self.run_worker(backend)
        self.assertEqual(result, 0)
        bodies = [call[3] for call in backend.calls if call[0] == 'PUT']
        self.assertEqual(len(bodies), 5)
        self.assertTrue(all(body == bodies[0] for body in bodies))

    def test_expired_lease_never_calls_provider_or_submits(self):
        backend = NormBackend()
        backend.claim['leaseExpiresAt'] = instant(-1)
        result, output, _, provider = self.run_worker(backend)
        self.assertEqual(result, 0)
        self.assertIn('lease_lost=1', output)
        provider.generate.assert_not_called()
        self.assertEqual(len(backend.calls), 1)

    def test_submit_retry_stops_at_deadline(self):
        clock = [100.0]
        backend = NormBackend()
        backend.hook = lambda *_: (503, {'code': 'NORMALIZATION_UNAVAILABLE'})
        with backend_server(backend) as url:
            client = BackendClient(replace(config(url), max_http_attempts=1), prefix=NORM_PREFIX)
            with (
                patch('cv_compass_llm.backend_client.time.monotonic', side_effect=lambda: clock[0]),
                patch(
                    'cv_compass_llm.backend_client.time.sleep',
                    side_effect=lambda _: clock.__setitem__(0, 103.0),
                ),
                self.assertRaises(BackendError) as caught,
            ):
                client.submit_norm(backend.claim['token'], RESULTS, deadline=102.0)
            self.assertEqual(caught.exception.code, 'BACKEND_DEADLINE_EXCEEDED')
        self.assertEqual(len(backend.calls), 1)

    def test_provider_failure_has_fixed_diagnostic_and_no_submit(self):
        backend = NormBackend()
        provider = Mock()
        provider.generate.side_effect = ProviderError('FORBIDDEN_PRIVATE_RESPONSE')
        result, output, logs, _ = self.run_worker(backend, provider)
        self.assertEqual(result, 2)
        self.assertIn('PROVIDER_ERROR=1', output)
        self.assertNotIn('FORBIDDEN', output + logs)
        self.assertEqual(len(backend.calls), 1)

    def test_invalid_output_submits_error_items_only(self):
        backend = NormBackend()
        provider = Mock()
        provider.generate.side_effect = lambda *_a, **_kw: '{"results":[],"extra":1}'
        self.run_worker(backend, provider)
        self.assertEqual(
            json.loads(backend.calls[-1][3])['results'],
            [{'id': item['id'], 'error': 'LLM_INVALID_JSON'} for item in ITEMS],
        )

    def test_worker_bounds_the_body_before_submitting(self):
        backend = NormBackend()
        provider = Mock()
        results = []
        items = []
        for index in range(1, 51):
            results.append(
                {
                    'id': index,
                    'company': {
                        'name': '🧪' * 255,
                        'parent': '🪁' * 255,
                        'industry': 'RETAIL',
                        'proposedLabel': '🧭' * 255,
                    },
                }
            )
            items.append(
                {'id': index, 'kind': 'COMPANY', 'raw': '🧪' * 255 + ' (' + '🪁' * 255 + ')'}
            )
        backend.claim['items'] = items
        provider.generate.side_effect = lambda *_a, **_kw: response(results)

        def hook(method, _path, _headers, body):
            if method == 'PUT':
                self.assertLessEqual(len(body), NORM_MAX_BODY_BYTES)
                rows = json.loads(body)['results']
                rejected = [
                    {'id': row['id'], 'code': row['error']} for row in rows if 'error' in row
                ]
                return 200, {'accepted': len(rows) - len(rejected), 'rejected': rejected}

        backend.hook = hook
        result, output, _, _ = self.run_worker(backend, provider)
        self.assertEqual(result, 0)
        submitted = json.loads(backend.calls[-1][3])['results']
        self.assertTrue(any(row.get('error') == 'RESULT_TOO_LARGE' for row in submitted))
        self.assertIn('RESULT_TOO_LARGE=', output)

    def test_rejected_submit_in_loop_is_dropped_and_next_batch_is_processed(self):
        for status in (400, 413):
            with self.subTest(status=status):
                client = Mock()
                first = NormBackend().claim
                second = first | {'token': 'b0000000-0000-4000-8000-000000000002'}
                client.claim_norm.side_effect = [first, second, KeyboardInterrupt]
                client.submit_norm.side_effect = [
                    BackendError('INVALID_RESULT' if status == 400 else 'RESULT_TOO_LARGE', status),
                    {'accepted': 2, 'rejected': []},
                ]
                provider = Mock()
                provider.generate.return_value = response()
                output = io.StringIO()
                with (
                    patch('cv_compass_llm.normalize.BackendClient', return_value=client),
                    patch('cv_compass_llm.normalize.time.sleep'),
                    redirect_stdout(output),
                    redirect_stderr(io.StringIO()),
                ):
                    result = run_normalize(NormConfig(config('http://127.0.0.1')), provider)
                self.assertEqual(result, 130)
                self.assertEqual(client.claim_norm.call_count, 3)
                self.assertEqual(client.submit_norm.call_count, 2)
                self.assertEqual(
                    [call.args[0] for call in client.submit_norm.call_args_list],
                    [first['token'], second['token']],
                )
                self.assertIn('dropped_batches=1', output.getvalue())
                self.assertIn('accepted=2', output.getvalue())

    def test_submit_auth_errors_still_stop_the_loop(self):
        for status in (401, 403):
            with self.subTest(status=status):
                client = Mock()
                client.claim_norm.return_value = NormBackend().claim
                client.submit_norm.side_effect = BackendError('UNAUTHORIZED', status)
                provider = Mock()
                provider.generate.return_value = response()
                with (
                    patch('cv_compass_llm.normalize.BackendClient', return_value=client),
                    redirect_stdout(io.StringIO()),
                    redirect_stderr(io.StringIO()),
                ):
                    result = run_normalize(NormConfig(config('http://127.0.0.1')), provider)
                self.assertEqual(result, 2)
                client.claim_norm.assert_called_once()
                client.submit_norm.assert_called_once()

    def test_claim_transport_error_in_loop_keeps_polling(self):
        client = Mock()
        client.claim_norm.side_effect = [
            BackendError('BACKEND_UNREACHABLE', 0),
            NormBackend().claim,
            KeyboardInterrupt,
        ]
        client.submit_norm.return_value = {'accepted': 2, 'rejected': []}
        provider = Mock()
        provider.generate.return_value = response()
        output = io.StringIO()
        with (
            patch('cv_compass_llm.normalize.BackendClient', return_value=client),
            patch('cv_compass_llm.normalize.time.sleep') as sleep,
            redirect_stdout(output),
            redirect_stderr(io.StringIO()),
        ):
            result = run_normalize(NormConfig(config('http://127.0.0.1')), provider)
        self.assertEqual(result, 130)
        self.assertEqual(client.claim_norm.call_count, 3)
        client.submit_norm.assert_called_once()
        self.assertIn('BACKEND_UNREACHABLE=1', output.getvalue())
        self.assertIn('accepted=2', output.getvalue())
        self.assertEqual(sleep.call_count, 2)

    def test_jackson_instant_formats_are_accepted(self):
        for timestamp, seconds in (
            ('2026-11-22T06:24:01.123456789Z', 1.123456),
            ('2026-11-22T06:24:01Z', 1.0),
        ):
            with self.subTest(timestamp=timestamp):
                client = Mock()
                client.claim_norm.return_value = NormBackend().claim | {'leaseExpiresAt': timestamp}
                client.submit_norm.return_value = {'accepted': 2, 'rejected': []}
                provider = Mock()
                provider.generate.return_value = response()
                with (
                    patch('cv_compass_llm.normalize.BackendClient', return_value=client),
                    patch('cv_compass_llm.normalize.datetime', wraps=datetime) as clock,
                    redirect_stdout(io.StringIO()),
                    redirect_stderr(io.StringIO()),
                ):
                    clock.now.return_value = datetime(2026, 11, 22, 6, 24, 0, tzinfo=timezone.utc)
                    started = time.monotonic()
                    result = run_normalize(
                        NormConfig(config('http://127.0.0.1')), provider, once=True
                    )
                    finished = time.monotonic()
                self.assertEqual(result, 0)
                provider.generate.assert_called_once()
                deadline = client.submit_norm.call_args.kwargs['deadline']
                self.assertGreaterEqual(deadline, started + seconds)
                self.assertLessEqual(deadline, finished + seconds)


class NormalizationConfigTests(unittest.TestCase):
    def test_env_example_documents_and_loads_the_separate_normalization_key(self):
        path = Path(__file__).resolve().parents[1] / '.env.example'
        text = path.read_text(encoding='utf-8')
        self.assertEqual(text.count('\n' + NORM_BATCH_KEY + '='), 1)
        self.assertEqual(_read_env(path, True)[NORM_BATCH_KEY], '50')
        cfg = load_norm_config(
            {'CVC_LLM_WORKER_TOKEN': TOKEN, 'CVC_LLM_MODEL': 'synthetic-model'},
            path,
            env_file_explicit=True,
        )
        self.assertEqual(cfg.batch, 50)

    def test_batch_defaults_bounds_env_precedence_and_cli(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'synthetic.env'
            path.write_text('CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic\n', encoding='utf-8')
            env = {'CVC_LLM_BACKEND_URL': 'http://127.0.0.1', 'CVC_LLM_WORKER_TOKEN': TOKEN}

            def load(values=env):
                return load_norm_config(values, path, env_file_explicit=True)

            self.assertEqual(load().batch, 50)
            with path.open('a', encoding='utf-8') as stream:
                stream.write('CVC_NORM_BATCH=7\n')
            self.assertEqual(load().batch, 7)
            self.assertEqual(load(env | {'CVC_NORM_BATCH': '1'}).batch, 1)
            for invalid in ('0', '51', '-1', '1.5', '', 'FORBIDDEN_VALUE'):
                with self.assertRaises(PullConfigError) as caught:
                    load(env | {'CVC_NORM_BATCH': invalid})
                self.assertEqual(str(caught.exception), 'CVC_NORM_BATCH INVALID_NORM_BATCH')
            output = io.StringIO()
            with (
                patch.dict('os.environ', env, clear=True),
                redirect_stdout(output),
                patch('cv_compass_llm.cli.create_provider', return_value=Mock()),
                patch('cv_compass_llm.cli.run_normalize', return_value=0) as runner,
            ):
                self.assertEqual(
                    main(['normalize', '--env-file', str(path), '--once', '--log-level', 'DEBUG']),
                    0,
                )
            self.assertEqual(runner.call_args.args[0].transport.log_level, 'DEBUG')
            self.assertEqual(runner.call_args.kwargs, {'once': True})


if __name__ == '__main__':
    unittest.main()
