from typing import Protocol


class ProviderError(Exception):
    """Provider failure; callers must not expose exception content."""


class LlmProvider(Protocol):
    name: str

    def generate(self, prompt: str, json_schema: dict) -> str:
        """Return raw JSON text or raise a provider failure."""
        ...
