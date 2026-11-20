"""Byte locks for fixed synthetic E10 request/result contracts."""

import hashlib
import unittest

from cv_compass_llm.pull_payload import PERSONAL, compact_result
from cv_compass_llm.schema import (
    Evidence,
    Experience,
    ExtractionResult,
    FieldValue,
    Issue,
)


class BaselineTests(unittest.TestCase):
    def test_compact_result_sha256(self):
        result = ExtractionResult(
            (
                FieldValue(
                    'fullName',
                    'Synthetic Person',
                    Evidence(1, 0, 16, 'Synthetic Person'),
                ),
            ),
            (
                Experience(
                    'Engineer',
                    None,
                    None,
                    None,
                    False,
                    {'title': Evidence(1, 20, 28, 'Engineer')},
                ),
            ),
            (Issue('INVALID_DATE', 'start'),),
            'synthetic',
            'fake',
        )
        claim = {
            'inputSha256': 'a' * 64,
            'definition': {
                'fields': {key: {'enabled': True} for key in (*PERSONAL, 'experiences')},
                'other': {'enabled': True, 'maxItems': 2},
            },
        }
        self.assertEqual(
            hashlib.sha256(compact_result(result, claim, {'worker': 'synthetic'})).hexdigest(),
            '4d17ea97ceb0bfed49e22c838cb69c3927a6d907aed9d9400912bca69626a147',
        )
