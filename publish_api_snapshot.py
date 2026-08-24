#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from pathlib import Path

import requests


PRIORITY_CONTESTS = (
    {
        "id": "pg-004-2024-acs",
        "title": "Concurso Público 004/2024 — Agente Comunitário de Saúde",
        "organization": "Prefeitura de Praia Grande",
        "city": "Praia Grande",
        "uf": "SP",
        "region": "Baixada Santista",
        "scope": "municipal",
        "type": "concurso público",
        "area": "Saúde",
        "status": "tracked",
        "source": "priority_scanner",
        "url": "https://www.praiagrande.sp.gov.br/administracao/concurso_publico.asp?cd_pagina=187",
        "priority": 120,
        "active": True,
    },
    {
        "id": "sv-02-2026-atg",
        "title": "Concurso Público 02/2026 — Assistente-Técnico de Gestão",
        "organization": "Prefeitura de São Vicente",
        "city": "São Vicente",
        "uf": "SP",
        "region": "Baixada Santista",
        "scope": "municipal",
        "type": "concurso público",
        "area": "Administrativo",
        "status": "tracked",
        "source": "priority_scanner",
        "url": "https://www.saovicente.sp.gov.br/institucional/concursos/concurso-no-02-2026",
        "priority": 120,
        "active": True,
    },
    {
        "id": "pg-002-2025-prof-mat",
        "title": "Concurso Público 002/2025 — Professor III Matemática",
        "organization": "Prefeitura de Praia Grande",
        "city": "Praia Grande",
        "uf": "SP",
        "region": "Baixada Santista",
        "scope": "municipal",
        "type": "concurso público",
        "area": "Matemática",
        "education": "nível superior",
        "status": "tracked",
        "source": "priority_scanner",
        "url": "https://www.praiagrande.sp.gov.br/administracao/concurso_publico.asp?cd_pagina=187",
        "priority": 120,
        "active": True,
    },
)


def load(path: str, default):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except Exception:
        return default


def build_payload(contests: dict, priority: dict) -> dict:
    canonical_items = [dict(item) for item in contests.get("items", []) if item.get("id")]
    by_id = {str(item["id"]): item for item in canonical_items}

    # Priority scanner documents/events have D1 FKs to contests. Seed a minimal,
    # stable parent only when the broad canonical feed does not already own it.
    for synthetic in PRIORITY_CONTESTS:
        by_id.setdefault(synthetic["id"], dict(synthetic))

    documents = list(priority.get("documents", []))
    events = list(priority.get("events", []))
    referenced = {
        str(item.get("contest_id"))
        for item in documents + events
        if item.get("contest_id")
    }
    missing = referenced - set(by_id)
    if missing:
        raise ValueError(f"priority contest FK parent missing: {sorted(missing)}")

    health_by_id = {}
    for item in contests.get("source_health", []) + priority.get("source_health", []):
        if item.get("id"):
            health_by_id[str(item["id"])] = item

    alerts = [
        {
            "event_id": event.get("id"),
            "title": event.get("title"),
            "body": event.get("body"),
            "url": event.get("url"),
            "priority": event.get("priority", 0),
            "created_at": event.get("created_at"),
        }
        for event in priority.get("new_events", [])
        if int(event.get("priority", 0)) >= 75
    ]

    return {
        "full_snapshot": True,
        "contests": list(by_id.values()),
        "source_health": list(health_by_id.values()),
        "documents": documents,
        "events": events,
        "alerts": alerts,
    }


def main() -> int:
    runtime = load("config/runtime.json", {})
    base = (os.getenv("CONCURSOS_API_URL") or runtime.get("cloudflare_url") or "").rstrip("/")
    token = os.getenv("WATCHDOG_TOKEN", "").strip()
    if not base or not token:
        print("[API] skip: CONCURSOS_API_URL/cloudflare_url ou WATCHDOG_TOKEN ausente")
        return 0

    contests = load("state/new_contests.json", {})
    priority = load("state/priority_events.json", {})
    payload = build_payload(contests, priority)

    response = requests.post(
        f"{base}/api/v1/ingest",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        json=payload,
        timeout=90,
    )
    response.raise_for_status()
    print("[API]", response.text[:1000])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
