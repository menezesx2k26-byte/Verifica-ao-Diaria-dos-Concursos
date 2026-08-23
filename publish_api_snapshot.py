#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from pathlib import Path

import requests


def load(path: str, default):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except Exception:
        return default


def main() -> int:
    runtime = load("config/runtime.json", {})
    base = (os.getenv("CONCURSOS_API_URL") or runtime.get("cloudflare_url") or "").rstrip("/")
    token = os.getenv("WATCHDOG_TOKEN", "").strip()
    if not base or not token:
        print("[API] skip: CONCURSOS_API_URL/cloudflare_url ou WATCHDOG_TOKEN ausente")
        return 0

    contests = load("state/new_contests.json", {})
    priority = load("state/priority_events.json", {})
    health_by_id = {}
    for item in contests.get("source_health", []) + priority.get("source_health", []):
        if item.get("id"):
            health_by_id[item["id"]] = item
    payload = {
        "full_snapshot": True,
        "contests": contests.get("items", []),
        "source_health": list(health_by_id.values()),
        "documents": priority.get("documents", []),
        "events": priority.get("events", []),
        "alerts": [
            {
                "event_id": e.get("id"), "title": e.get("title"), "body": e.get("body"),
                "url": e.get("url"), "priority": e.get("priority", 0), "created_at": e.get("created_at"),
            }
            for e in priority.get("new_events", [])
            if int(e.get("priority", 0)) >= 75
        ],
    }
    r = requests.post(
        f"{base}/api/v1/ingest",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        json=payload,
        timeout=90,
    )
    r.raise_for_status()
    print("[API]", r.text[:1000])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
