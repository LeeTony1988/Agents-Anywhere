"""The shared secret signs access tokens, so startup must flag a weak one."""

from __future__ import annotations

import pytest

from agent_server.core.auth import (
    DEFAULT_SECRET,
    MIN_SECRET_LENGTH,
    create_user_access_token,
    secret_problem,
)


def test_missing_secret_is_reported(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AGENT_SERVER_SECRET", raising=False)
    assert secret_problem() == "unset"


def test_default_secret_is_reported(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_SERVER_SECRET", DEFAULT_SECRET)
    assert secret_problem() == "default"


def test_short_secret_is_reported(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_SERVER_SECRET", "a" * (MIN_SECRET_LENGTH - 1))
    assert secret_problem() == "too_short"


def test_long_random_secret_is_accepted(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_SERVER_SECRET", "x" * MIN_SECRET_LENGTH)
    assert secret_problem() is None
    # Explicit values bypass the environment (used by tests and tools).
    assert secret_problem("y" * MIN_SECRET_LENGTH) is None


def test_tokens_are_signed_with_the_configured_secret(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("AGENT_SERVER_SECRET", "s" * MIN_SECRET_LENGTH)
    first = create_user_access_token("usr_1", expires_in=60)
    monkeypatch.setenv("AGENT_SERVER_SECRET", "t" * MIN_SECRET_LENGTH)
    second = create_user_access_token("usr_1", expires_in=60)
    assert first != second
