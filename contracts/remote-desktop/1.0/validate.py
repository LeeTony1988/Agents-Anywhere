"""Validate the remote-desktop/1.0 contract.

The schemas are the single source of truth for the wire format. This checker
mirrors ``contracts/runtime-control/2.1/validate.py`` and additionally checks
the contract metadata that generated client types depend on.

    uv run --with jsonschema --with referencing python \
        contracts/remote-desktop/1.0/validate.py
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parent
SCHEMA_DIR = ROOT / "schemas"
FIXTURE_DIR = ROOT / "fixtures"
CONTRACT_VERSION = "1.0"


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise TypeError(f"{path.relative_to(ROOT)} must contain a JSON object")
    return value


def sha256(path: Path) -> str:
    # Hash LF-normalised bytes: Git checks these files out with CRLF on Windows,
    # and the manifest must stay valid on every platform (the TypeScript
    # generator normalises the same way).
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def load_contract() -> tuple[dict[str, dict[str, Any]], Registry[Any]]:
    schema_paths = sorted(SCHEMA_DIR.glob("*.schema.json"))
    if not schema_paths:
        raise RuntimeError("no remote-desktop schemas found")
    schemas: dict[str, dict[str, Any]] = {}
    registry = Registry()
    for path in schema_paths:
        schema = load_json(path)
        try:
            Draft202012Validator.check_schema(schema)
        except SchemaError as exc:
            raise AssertionError(f"{path.name} is not valid JSON Schema: {exc.message}") from exc
        slug = path.name[: -len(".schema.json")]
        schema_id = schema.get("$id")
        if not isinstance(schema_id, str) or not schema_id:
            raise ValueError(f"{path.relative_to(ROOT)} is missing $id")
        if schema_id != f"urn:agents-anywhere:remote-desktop:{CONTRACT_VERSION}:{slug}":
            raise ValueError(f"{path.name} has unexpected $id: {schema_id}")
        if schema.get("x-contract-version") != CONTRACT_VERSION:
            raise ValueError(f"{path.name} has unexpected x-contract-version")
        if schema.get("x-direction") not in {"ingress", "egress", "bidirectional"}:
            raise ValueError(f"{path.name} has unexpected x-direction")
        schemas[slug] = schema
        registry = registry.with_resource(schema_id, Resource.from_contents(schema))
    return schemas, registry


def check_manifest(schemas: dict[str, dict[str, Any]]) -> None:
    manifest = load_json(ROOT / "manifest.json")
    if manifest.get("contractVersion") != CONTRACT_VERSION:
        raise ValueError("manifest contractVersion mismatch")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list):
        raise TypeError("manifest artifacts must be an array")

    manifest_slugs: set[str] = set()
    manifest_paths: set[Path] = set()
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise TypeError("manifest artifact must be an object")
        slug = artifact.get("slug")
        relative_path = artifact.get("path")
        expected_hash = artifact.get("sha256")
        direction = artifact.get("direction")
        if not all(isinstance(value, str) for value in (slug, relative_path, expected_hash, direction)):
            raise TypeError("manifest artifact fields must be strings")
        assert isinstance(slug, str) and isinstance(relative_path, str)
        assert isinstance(expected_hash, str) and isinstance(direction, str)
        if slug in manifest_slugs:
            raise ValueError(f"duplicate manifest slug: {slug}")
        manifest_slugs.add(slug)
        path = ROOT / relative_path
        if path.parent != SCHEMA_DIR or path.name not in {
            f"{name}.schema.json" for name in schemas
        }:
            raise ValueError(f"manifest references an unknown schema: {relative_path}")
        manifest_paths.add(path)
        actual = sha256(path)
        if actual != expected_hash:
            raise ValueError(
                f"sha256 mismatch for {relative_path}: {actual} != {expected_hash}"
            )
        schema = schemas[slug]
        if schema["x-direction"] != direction:
            raise ValueError(
                f"manifest direction for {slug} is {direction}, "
                f"schema says {schema['x-direction']}"
            )

    missing = sorted(set(schemas) - manifest_slugs)
    if missing:
        raise ValueError(f"schemas missing from manifest: {missing}")
    extra = sorted(manifest_slugs - set(schemas))
    if extra:
        raise ValueError(f"manifest declares unknown schemas: {extra}")


def validate_fixtures(
    kind: str,
    schemas: dict[str, dict[str, Any]],
    registry: Registry[Any],
    format_checker: FormatChecker,
    *,
    expect_valid: bool,
) -> int:
    count = 0
    seen_slugs: set[str] = set()
    for fixture_path in sorted((FIXTURE_DIR / kind).glob("*/*.json")):
        slug = fixture_path.parent.name
        seen_slugs.add(slug)
        schema = schemas.get(slug)
        if schema is None:
            raise ValueError(f"{fixture_path.relative_to(ROOT)} has no matching schema")
        validator = Draft202012Validator(
            schema, registry=registry, format_checker=format_checker
        )
        payload = load_json(fixture_path)
        if expect_valid:
            validator.validate(payload)
        else:
            try:
                validator.validate(payload)
            except ValidationError:
                pass
            else:
                raise AssertionError(
                    f"invalid fixture was accepted: {fixture_path.relative_to(ROOT)}"
                )
        count += 1
    return count


def main() -> None:
    schemas, registry = load_contract()
    check_manifest(schemas)

    valid_dir = FIXTURE_DIR / "valid"
    missing_valid = sorted(
        slug for slug in schemas if not any((valid_dir / slug).glob("*.json"))
    )
    if missing_valid:
        raise ValueError(f"every schema needs a valid fixture: {missing_valid}")

    format_checker = FormatChecker()
    valid_count = validate_fixtures(
        "valid", schemas, registry, format_checker, expect_valid=True
    )
    invalid_count = validate_fixtures(
        "invalid", schemas, registry, format_checker, expect_valid=False
    )

    flat = [
        path
        for kind in ("valid", "invalid")
        for path in (FIXTURE_DIR / kind).glob("*.json")
    ]
    if flat:
        raise ValueError(
            "fixtures must live under fixtures/<kind>/<slug>/: "
            + ", ".join(str(path.relative_to(ROOT)) for path in flat)
        )

    print(
        f"OK {len(schemas)} schemas, {valid_count} valid fixtures, "
        f"{invalid_count} invalid fixtures"
    )


if __name__ == "__main__":
    main()
