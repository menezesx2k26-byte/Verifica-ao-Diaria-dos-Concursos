#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

PLACEHOLDER = "00000000-0000-4000-8000-000000000000"
UUID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
API = "https://api.cloudflare.com/client/v4"


def render_config(template: str, database_id: str) -> str:
    if not UUID.fullmatch(database_id):
        raise ValueError("invalid D1 database id")
    if PLACEHOLDER not in template:
        raise ValueError("D1 placeholder missing from Wrangler template")
    return template.replace(PLACEHOLDER, database_id)


def _request(path: str, method: str = "GET", body: dict | None = None) -> dict:
    account = os.environ["CLOUDFLARE_ACCOUNT_ID"]
    token = os.environ["CLOUDFLARE_API_TOKEN"]
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        f"{API}{path}",
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:1600]
        raise RuntimeError(f"Cloudflare API {exc.code}: {detail}") from exc
    if payload.get("success") is False:
        raise RuntimeError(f"Cloudflare API error: {payload.get('errors')}")
    return payload


def resolve_database_id(name: str = "concursos-watch") -> str:
    account = os.environ["CLOUDFLARE_ACCOUNT_ID"]
    payload = _request(f"/accounts/{account}/d1/database?per_page=100")
    rows = payload.get("result") or []
    for row in rows:
        if row.get("name") == name:
            database_id = row.get("uuid") or row.get("id")
            if database_id:
                return str(database_id)
    created = _request(
        f"/accounts/{account}/d1/database",
        method="POST",
        body={"name": name},
    ).get("result") or {}
    database_id = created.get("uuid") or created.get("id")
    if not database_id:
        raise RuntimeError("Cloudflare did not return D1 database id")
    return str(database_id)


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    source = Path(args[0] if len(args) >= 1 else "edge/wrangler.toml")
    target = Path(args[1] if len(args) >= 2 else "edge/wrangler.generated.toml")
    database = os.getenv("CF_D1_DATABASE_NAME", "concursos-watch")
    database_id = resolve_database_id(database)
    rendered = render_config(source.read_text(encoding="utf-8"), database_id)
    target.write_text(rendered, encoding="utf-8")
    print(f"edge Wrangler config OK: DB={database_id[:8]}… -> {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
