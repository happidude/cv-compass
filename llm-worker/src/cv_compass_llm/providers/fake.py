"""Synthetic provider; recorded prompts must remain private in-memory."""

import json

from .base import ProviderError


class FakeProvider:
    name = 'fake'

    def __init__(self, responses: list[str | dict | ProviderError] | dict) -> None:
        """Store predetermined responses for synthetic in-memory tests."""
        self.responses = list(responses) if isinstance(responses, list) else [responses]
        self.prompts: list[str] = []

    def generate(self, prompt: str, json_schema: dict) -> str:
        """Record the prompt and consume one predetermined response."""
        self.prompts.append(prompt)
        if not self.responses:
            raise ProviderError('FAKE_EXHAUSTED')
        response = self.responses.pop(0)
        if isinstance(response, ProviderError):
            raise response
        return json.dumps(response) if isinstance(response, dict) else response
