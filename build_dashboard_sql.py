#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path

from validate_dashboard_config import load_and_validate, validate_dashboard_config


def sql_text(value: object) -> str:
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def build_draft_sql(data: dict) -> str:
    validate_dashboard_config(data)
    version = int(data["dashboard_version"])
    schema_version = int(data["schema_version"])
    style_version = int(data["style_version"])
    min_app_version = sql_text(data["min_app_version"])
    sections_json = sql_text(
        json.dumps(data["sections"], ensure_ascii=False, separators=(",", ":"))
    )
    return "\n".join(
        [
            "BEGIN IMMEDIATE;",
            "INSERT INTO dashboard_configs "
            "(version, schema_version, style_version, min_app_version, published_at, sections_json, status) "
            f"VALUES ({version}, {schema_version}, {style_version}, {min_app_version}, "
            f"'1970-01-01T00:00:00Z', {sections_json}, 'draft') "
            "ON CONFLICT(version) DO UPDATE SET "
            "schema_version=excluded.schema_version, "
            "style_version=excluded.style_version, "
            "min_app_version=excluded.min_app_version, "
            "sections_json=excluded.sections_json, "
            "status='draft' "
            "WHERE dashboard_configs.status <> 'published';",
            "COMMIT;",
            "",
        ]
    )


def build_promotion_sql(version: int) -> str:
    if isinstance(version, bool) or not isinstance(version, int) or version <= 0:
        raise ValueError("dashboard version must be a positive integer")
    return "\n".join(
        [
            "BEGIN IMMEDIATE;",
            "UPDATE dashboard_configs "
            "SET status='published', published_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') "
            f"WHERE version={version} AND status='draft';",
            "UPDATE dashboard_configs SET status='superseded' "
            f"WHERE status='published' AND version<>{version} "
            f"AND EXISTS (SELECT 1 FROM dashboard_configs WHERE version={version} AND status='published');",
            "COMMIT;",
            "",
        ]
    )


def write_sql_files(config_path: Path, draft_path: Path, promote_path: Path) -> None:
    data = load_and_validate(config_path)
    draft_path.parent.mkdir(parents=True, exist_ok=True)
    promote_path.parent.mkdir(parents=True, exist_ok=True)
    draft_path.write_text(build_draft_sql(data), encoding="utf-8")
    promote_path.write_text(
        build_promotion_sql(int(data["dashboard_version"])), encoding="utf-8"
    )


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    config_path = Path(args[0] if len(args) >= 1 else "config/dashboard.json")
    draft_path = Path(args[1] if len(args) >= 2 else "state/dashboard_publish.sql")
    promote_path = Path(args[2] if len(args) >= 3 else "state/dashboard_promote.sql")
    write_sql_files(config_path, draft_path, promote_path)
    print(f"dashboard SQL OK: {draft_path} / {promote_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
