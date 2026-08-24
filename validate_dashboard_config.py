#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

TOP_LEVEL_KEYS = {
    "schema_version",
    "dashboard_version",
    "style_version",
    "min_app_version",
    "sections",
}
SECTION_KEYS: dict[str, set[str]] = {
    "attention": {"type", "limit"},
    "priority_watch": {"type"},
    "open_contests": {"type", "limit"},
}
SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$")


def _positive_int(value: object, name: str, *, maximum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError(f"{name} must be a positive integer")
    if maximum is not None and value > maximum:
        raise ValueError(f"{name} exceeds maximum {maximum}")
    return value


def validate_dashboard_config(data: dict) -> None:
    if not isinstance(data, dict):
        raise ValueError("dashboard config must be an object")
    unknown = set(data) - TOP_LEVEL_KEYS
    missing = TOP_LEVEL_KEYS - set(data)
    if unknown:
        raise ValueError(f"unknown dashboard keys: {sorted(unknown)}")
    if missing:
        raise ValueError(f"missing dashboard keys: {sorted(missing)}")
    if data["schema_version"] != 1:
        raise ValueError("unsupported dashboard schema")
    _positive_int(data["dashboard_version"], "dashboard_version")
    _positive_int(data["style_version"], "style_version")
    if not isinstance(data["min_app_version"], str) or not SEMVER.fullmatch(data["min_app_version"]):
        raise ValueError("min_app_version must be semantic version text")

    sections = data["sections"]
    if not isinstance(sections, list) or not sections:
        raise ValueError("sections must be a non-empty list")
    if len(sections) > 20:
        raise ValueError("too many dashboard sections")

    for index, section in enumerate(sections):
        if not isinstance(section, dict):
            raise ValueError(f"section {index} must be an object")
        kind = section.get("type")
        if kind not in SECTION_KEYS:
            raise ValueError(f"unknown section type: {kind!r}")
        unknown_section_keys = set(section) - SECTION_KEYS[kind]
        if unknown_section_keys:
            raise ValueError(
                f"unknown keys for section {kind}: {sorted(unknown_section_keys)}"
            )
        if kind in {"attention", "open_contests"}:
            _positive_int(section.get("limit"), f"{kind}.limit", maximum=20)


def load_and_validate(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    validate_dashboard_config(data)
    return data


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    path = Path(args[0] if args else "config/dashboard.json")
    data = load_and_validate(path)
    print(
        f"dashboard config OK: version={data['dashboard_version']} "
        f"sections={len(data['sections'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
