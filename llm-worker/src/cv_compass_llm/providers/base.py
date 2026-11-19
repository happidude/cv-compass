from typing import Protocol


class ProviderError(Exception):
    """Provider failure; callers must not expose exception content."""


class LlmProvider(Protocol):
    name: str
    # Optional telemetry: callers use getattr for providers without metrics.
    last_metrics: dict[str, int | str] | None

    def generate(
        self,
        prompt: str,
        json_schema: dict,
        *,
        num_ctx: int | None = None,
        timeout_s: float | None = None,
    ) -> str:
        """Return raw JSON text or raise a provider failure."""
        ...
