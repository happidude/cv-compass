"""Synthetic provider; recorded prompts must remain private in-memory."""

import json

from ..config import LlmConfig
from .base import ProviderError


class FakeProvider:
    name = 'fake'

    def __init__(
        self,
        responses: list[str | dict | ProviderError] | dict,
        *,
        metrics: list[dict] | dict | None = None,
        num_predict: int | None = None,
        keep_alive: str | None = None,
        extraction_config: LlmConfig | None = None,
    ) -> None:
        """Store predetermined responses for synthetic in-memory tests."""
        self.responses = list(responses) if isinstance(responses, list) else [responses]
        self.prompts: list[str] = []
        self.calls: list[tuple[dict, int | None, float | None]] = []
        self.metrics = metrics
        self.last_metrics: dict = {}
        self.num_predict = num_predict
        self.keep_alive = keep_alive
        self.extraction_config = extraction_config
        self.request_options: list[dict] = []

    def generate(
        self,
        prompt: str,
        json_schema: dict,
        *,
        num_ctx: int | None = None,
        timeout_s: float | None = None,
    ) -> str:
        """Record the prompt and consume one predetermined response."""
        self.last_metrics = {}
        metrics = self.metrics
        if isinstance(metrics, list):
            metrics = metrics.pop(0) if metrics else None
        self.prompts.append(prompt)
        self.calls.append((json_schema, num_ctx, timeout_s))
        self.request_options.append(
            {'num_predict': self.num_predict, 'keep_alive': self.keep_alive}
        )
        if not self.responses:
            raise ProviderError('FAKE_EXHAUSTED')
        response = self.responses.pop(0)
        if isinstance(response, ProviderError):
            raise response
        self.last_metrics = dict(metrics or {})
        return json.dumps(response) if isinstance(response, dict) else response
