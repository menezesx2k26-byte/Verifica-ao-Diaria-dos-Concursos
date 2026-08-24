#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass


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
