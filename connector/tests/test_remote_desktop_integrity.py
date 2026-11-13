from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from connector.local.remote_desktop.host import (
    ScreenHostIntegrityError,
    ScreenHostNotInstalled,
    default_screen_host_path,
    verify_screen_host,
)


def _bundle(root: Path, *, payload: bytes = b"host-binary", declared: str | None = None) -> Path:
    platform_arch = root / "windows-x86_64"
    platform_arch.mkdir(parents=True, exist_ok=True)
    binary = platform_arch / "aa-screen-host.exe"
    binary.write_bytes(payload)
    manifest = {
        "platform": "windows",
        "arch": "x86_64",
        "version": "aa-screen-host 0.1.0",
        "sha256": declared
        if declared is not None
        else hashlib.sha256(payload).hexdigest(),
        "features": [],
    }
    (platform_arch / "manifest.json").write_text(
        json.dumps(manifest), encoding="utf-8"
    )
    return binary


def test_default_path_finds_a_bundled_host_and_accepts_a_matching_manifest(
    tmp_path: Path,
) -> None:
    binary = _bundle(tmp_path / "resources")
    resolved = default_screen_host_path(
        platform="windows", arch="x86_64", roots=[tmp_path / "resources"]
    )
    assert resolved == binary


def test_tampered_binary_is_rejected(tmp_path: Path) -> None:
    binary = _bundle(tmp_path / "resources")
    binary.write_bytes(b"host-binary-with-an-injected-payload")
    with pytest.raises(ScreenHostIntegrityError) as raised:
        default_screen_host_path(
            platform="windows", arch="x86_64", roots=[tmp_path / "resources"]
        )
    assert raised.value.code == "rd_host_integrity"
    # The supervisor treats a bad bundle as "not installed", so the capability
    # stays unavailable instead of crashing the Connector.
    assert isinstance(raised.value, ScreenHostNotInstalled)


def test_manifest_without_sha256_is_rejected(tmp_path: Path) -> None:
    binary = _bundle(tmp_path / "resources")
    manifest_path = binary.parent / "manifest.json"
    manifest = json.loads(manifest_path.read_text("utf-8"))
    manifest.pop("sha256")
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ScreenHostIntegrityError):
        verify_screen_host(binary)


def test_missing_manifest_keeps_developer_bundles_working(tmp_path: Path) -> None:
    platform_arch = tmp_path / "resources" / "windows-x86_64"
    platform_arch.mkdir(parents=True)
    binary = platform_arch / "aa-screen-host.exe"
    binary.write_bytes(b"dev-binary")
    verify_screen_host(binary)
    assert (
        default_screen_host_path(
            platform="windows", arch="x86_64", roots=[tmp_path / "resources"]
        )
        == binary
    )


def test_checksum_comparison_is_case_insensitive(tmp_path: Path) -> None:
    payload = b"host-binary"
    binary = _bundle(
        tmp_path / "resources",
        payload=payload,
        declared=hashlib.sha256(payload).hexdigest().upper(),
    )
    verify_screen_host(binary)
