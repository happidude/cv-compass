"""Shared strict JSON parsing with content-free errors."""

import json


def strict_json(text: str) -> object:
    """Parse JSON while rejecting duplicate keys and nonfinite constants."""

    def pairs(items: list[tuple[str, object]]) -> dict:
        """Reject repeated object keys before building the mapping."""
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('INVALID_JSON')
            result[key] = value
        return result

    def constant(value: str) -> object:
        """Reject constants outside the JSON specification."""
        raise ValueError('INVALID_JSON')

    try:
        return json.loads(text, object_pairs_hook=pairs, parse_constant=constant)
    except ValueError, RecursionError:
        raise ValueError('INVALID_JSON') from None
