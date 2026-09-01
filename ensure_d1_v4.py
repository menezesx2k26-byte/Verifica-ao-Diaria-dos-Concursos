#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CLOUDFLARE_DIR = ROOT / "cloudflare"
DATABASE = os.getenv("CF_D1_DATABASE_NAME", "concursos-watch")


def schema_action(columns: list[dict]) -> str:
    names = {str(row.get("name", "")) for row in columns}
    if not names:
        return "schema"
    if "relevance_status" not in names or "relevance_confidence" not in names:
        return "migration"
    return "reconcile"


def _run_wrangler(*args: str, capture: bool = False) -> str:
    command = ["npx", "wrangler", *args]
    completed = subprocess.run(
        command,
        cwd=CLOUDFLARE_DIR,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return completed.stdout if capture else ""


def _extract_results(payload: object) -> list[dict]:
    if isinstance(payload, list):
        for item in payload:
            found = _extract_results(item)
            if found:
                return found
        return []
    if isinstance(payload, dict):
        results = payload.get("results")
        if isinstance(results, list):
            return [row for row in results if isinstance(row, dict)]
        for value in payload.values():
            found = _extract_results(value)
            if found:
                return found
    return []


def remote_columns() -> list[dict]:
    raw = _run_wrangler(
        "d1",
        "execute",
        DATABASE,
        "--remote",
        "--command",
        "PRAGMA table_info(contests);",
        "--json",
        capture=True,
    )
    return _extract_results(json.loads(raw))


def ensure_remote_schema() -> str:
    action = schema_action(remote_columns())
    if action == "schema":
        sql_file = "schema.sql"
    elif action == "migration":
        sql_file = "migrations/0002_v4.sql"
    else:
        sql_file = "schema.sql"

    _run_wrangler(
        "d1",
        "execute",
        DATABASE,
        "--remote",
        f"--file={sql_file}",
    )

    columns = {str(row.get("name", "")) for row in remote_columns()}
    required = {"relevance_status", "relevance_confidence"}
    if not required.issubset(columns):
        raise RuntimeError(f"D1 v4 columns missing after {action}: {sorted(required - columns)}")
    print(f"D1 schema v4 OK ({action})")
    return action


def main() -> int:
    if not os.getenv("CLOUDFLARE_API_TOKEN") or not os.getenv("CLOUDFLARE_ACCOUNT_ID"):
        raise SystemExit("CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID are required")
    ensure_remote_schema()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
