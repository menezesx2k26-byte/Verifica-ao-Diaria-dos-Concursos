#!/usr/bin/env python3
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Snapshot:
    contests: tuple[dict, ...]
    documents: tuple[dict, ...]
    events: tuple[dict, ...]
    alerts: tuple[dict, ...]
    source_health: tuple[dict, ...]


def build_snapshot(contests_state: dict, priority_state: dict) -> Snapshot:
    contests = tuple(
        dict(item)
        for item in contests_state.get("items", [])
        if item.get("relevance_status", "ACCEPTED") == "ACCEPTED"
    )
    return Snapshot(
        contests=contests,
        documents=tuple(priority_state.get("documents", [])),
        events=tuple(priority_state.get("events", [])),
        alerts=tuple(priority_state.get("new_events", [])),
        source_health=tuple(contests_state.get("source_health", []))
        + tuple(priority_state.get("source_health", [])),
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
