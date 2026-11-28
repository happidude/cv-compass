"""Anthropic messages with structured JSON output and a reduced schema copy."""

import copy

from .openai_api import _ApiProvider

ANTHROPIC_URL = 'https://api.anthropic.com/v1/messages'
_UNSUPPORTED = frozenset(
    {
        'minItems',
        'maxItems',
        'minimum',
        'maximum',
        'exclusiveMinimum',
        'exclusiveMaximum',
        'multipleOf',
        'minLength',
        'maxLength',
    }
)


def _reduce_schema(schema):
    """Remove unsupported validation keywords while preserving property names."""
    if not isinstance(schema, dict):
        return
    for key in _UNSUPPORTED:
        schema.pop(key, None)
    for key, value in schema.items():
        if key in ('properties', '$defs', 'definitions', 'patternProperties'):
            for child in value.values():
                _reduce_schema(child)
        elif isinstance(value, dict):
            _reduce_schema(value)
        elif isinstance(value, list):
            for child in value:
                _reduce_schema(child)


class AnthropicProvider(_ApiProvider):
    provider = 'anthropic'
    api_host = 'api.anthropic.com'
    url = ANTHROPIC_URL
    default_effort = 'low'
    efforts = ('low', 'medium', 'high')
    usage_fields = _ApiProvider.usage_fields + (
        (('cache_read_input_tokens',), 'cached_input_tokens'),
        (('cache_creation_input_tokens',), 'cache_write_tokens'),
    )

    def _headers(self):
        return {
            'x-api-key': self._api_key,
            'anthropic-version': '2023-06-01',
            'content-type': 'application/json',
        }

    def _payload(self, prompt, json_schema):
        sent_schema = copy.deepcopy(json_schema)
        _reduce_schema(sent_schema)
        return {
            'model': self.model,
            'max_tokens': self.max_output_tokens or 8192,
            'messages': [{'role': 'user', 'content': prompt}],
            'thinking': {'type': 'disabled'},
            'output_config': {
                'effort': self.reasoning_effort,
                'format': {'type': 'json_schema', 'schema': sent_schema},
            },
        }

    def _text(self, root):
        if root['stop_reason'] == 'refusal':
            raise ValueError
        parts = []
        for content in root['content']:
            if content.get('type') == 'refusal':
                raise ValueError
            if content.get('type') == 'text':
                parts.append(content['text'])
        return ''.join(parts), 'length' if root['stop_reason'] == 'max_tokens' else 'stop'
