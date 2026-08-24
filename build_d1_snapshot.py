#!/usr/bin/env python3
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


PRIORITY_CONTESTS = {
    "pg-004-2024-acs": {
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
        "relevance_status": "ACCEPTED",
        "relevance_confidence": 100,
    },
    "sv-02-2026-atg": {
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
        "relevance_status": "ACCEPTED",
        "relevance_confidence": 100,
    },
    "pg-002-2025-prof-mat": {
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
        "relevance_status": "ACCEPTED",
        "relevance_confidence": 100,
    },
}


@dataclass(frozen=True)
class Snapshot:
    contests: tuple[dict, ...]
    documents: tuple[dict, ...]
    events: tuple[dict, ...]
    alerts: tuple[dict, ...]
    source_health: tuple[dict, ...]


def build_snapshot(contests_state: dict, priority_state: dict) -> Snapshot:
    accepted_by_id = {
        str(item["id"]): dict(item)
        for item in contests_state.get("items", [])
        if item.get("id") and item.get("relevance_status", "ACCEPTED") == "ACCEPTED"
    }
    documents = tuple(dict(item) for item in priority_state.get("documents", []))
    events = tuple(dict(item) for item in priority_state.get("events", []))
    referenced = {
        str(item.get("contest_id"))
        for item in documents + events
        if item.get("contest_id")
    }
    stamp = (
        priority_state.get("updated_at")
        or contests_state.get("updated_at")
        or "1970-01-01T00:00:00Z"
    )
    for contest_id in sorted(referenced):
        if contest_id in accepted_by_id:
            continue
        synthetic = PRIORITY_CONTESTS.get(contest_id)
        if synthetic is None:
            raise ValueError(f"priority contest FK parent missing: {contest_id}")
        parent = dict(synthetic)
        parent.setdefault("first_seen", stamp)
        parent.setdefault("last_seen", stamp)
        parent.setdefault("updated_at", stamp)
        accepted_by_id[contest_id] = parent

    health_by_id: dict[str, dict] = {}
    for item in contests_state.get("source_health", []) + priority_state.get("source_health", []):
        if item.get("id"):
            health_by_id[str(item["id"])] = dict(item)

    alerts = tuple(
        {
            "event_id": event.get("id"),
            "title": event.get("title", ""),
            "body": event.get("body", ""),
            "url": event.get("url", ""),
            "priority": event.get("priority", 0),
            "created_at": event.get("created_at", stamp),
        }
        for event in priority_state.get("new_events", [])
        if int(event.get("priority", 0) or 0) >= 75
    )

    return Snapshot(
        contests=tuple(accepted_by_id.values()),
        documents=documents,
        events=events,
        alerts=alerts,
        source_health=tuple(health_by_id.values()),
    )


def sql_text(value: object) -> str:
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def sql_int(value: object, default: int = 0) -> str:
    if isinstance(value, bool):
        return "1" if value else "0"
    try:
        return str(int(value))
    except (TypeError, ValueError):
        return str(default)


def _contest_sql(item: dict) -> str:
    source_url = item.get("source_url") or item.get("url") or ""
    first_seen = item.get("first_seen") or item.get("updated_at") or item.get("last_seen") or ""
    last_seen = item.get("last_seen") or item.get("updated_at") or first_seen
    updated_at = item.get("updated_at") or last_seen or first_seen
    columns = (
        "id", "organization", "notice_number", "year", "title", "city", "uf", "region", "scope",
        "board", "type", "education", "area", "remuneration", "vacancies", "fee",
        "registration_start", "registration_end", "status", "source", "source_url", "edital_url",
        "priority", "active", "relevance_status", "relevance_confidence", "first_seen", "last_seen", "updated_at",
    )
    values = (
        sql_text(item.get("id", "")),
        sql_text(item.get("organization", "")),
        sql_text(item.get("notice_number", "")),
        "NULL" if item.get("year") in (None, "") else sql_int(item.get("year")),
        sql_text(item.get("title", "")),
        sql_text(item.get("city", "")),
        sql_text(item.get("uf", "")),
        sql_text(item.get("region", "")),
        sql_text(item.get("scope", "")),
        sql_text(item.get("board", "")),
        sql_text(item.get("type", "")),
        sql_text(item.get("education", "")),
        sql_text(item.get("area", "")),
        sql_text(item.get("remuneration", "")),
        sql_text(item.get("vacancies", "")),
        sql_text(item.get("fee", "")),
        sql_text(item.get("registration_start") or item.get("start_date") or ""),
        sql_text(item.get("registration_end") or item.get("end_date") or ""),
        sql_text(item.get("status", "detected")),
        sql_text(item.get("source", "")),
        sql_text(source_url),
        sql_text(item.get("edital_url", "")),
        sql_int(item.get("priority"), 50),
        sql_int(item.get("active", True), 1),
        sql_text("ACCEPTED"),
        sql_int(item.get("relevance_confidence"), 100),
        sql_text(first_seen),
        sql_text(last_seen),
        sql_text(updated_at),
    )
    updates = ", ".join(
        f"{column}=excluded.{column}"
        for column in columns
        if column != "id"
    )
    return (
        f"INSERT INTO contests ({', '.join(columns)}) VALUES ({', '.join(values)}) "
        f"ON CONFLICT(id) DO UPDATE SET {updates};"
    )


def write_sql(snapshot: Snapshot, path: Path) -> None:
    lines = ["BEGIN IMMEDIATE;", "UPDATE contests SET active=0;"]
    lines.extend(_contest_sql(item) for item in snapshot.contests if item.get("id"))
    lines.append("COMMIT;")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def load(path: Path, default: dict) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def main() -> int:
    contests = load(Path("state/new_contests.json"), {})
    priority = load(Path("state/priority_events.json"), {})
    snapshot = build_snapshot(contests, priority)
    output = Path("state/d1_snapshot.sql")
    write_sql(snapshot, output)
    print(
        f"[D1] contests={len(snapshot.contests)} documents={len(snapshot.documents)} "
        f"events={len(snapshot.events)} health={len(snapshot.source_health)} -> {output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
