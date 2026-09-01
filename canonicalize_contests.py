#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse

FEED = Path("state/new_contests.json")
HISTORY = Path("state/contest_identity_history.json")
TRACKING = ("utm_", "fbclid", "gclid")


def load(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def save(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def fold(text: str) -> str:
    text = unicodedata.normalize("NFKD", text or "")
    return " ".join("".join(c for c in text if not unicodedata.combining(c)).casefold().split())


def canonical_url(raw: str) -> str:
    p = urlparse(raw or "")
    q = [(k, v) for k, v in parse_qsl(p.query, keep_blank_values=True) if not k.casefold().startswith(TRACKING)]
    return urlunparse((p.scheme.casefold(), p.netloc.casefold(), re.sub(r"//+", "/", p.path or "/").rstrip("/") or "/", "", urlencode(q), ""))


def notice_identity(item: dict) -> tuple[str, int | None]:
    corpus = fold(f"{item.get('title','')} {item.get('url','')}")
    patterns = (
        r"(?:edital|concurso|processo seletivo|pss)\s*(?:n[ºo°.]*)?\s*([0-9]{1,4})\s*[/_-]\s*(20[0-9]{2})",
        r"\b([0-9]{1,4})\s*[/_-]\s*(20[0-9]{2})\b",
    )
    for pattern in patterns:
        m = re.search(pattern, corpus)
        if m:
            return f"{int(m.group(1)):03d}/{m.group(2)}", int(m.group(2))
    return "", None


def identity_key(item: dict) -> str:
    source = str(item.get("source_id") or item.get("organization") or "unknown")
    notice, _ = notice_identity(item)
    if notice:
        return f"notice:{source}:{notice}"
    return f"url:{source}:{canonical_url(str(item.get('url') or ''))}"


def generated_id(key: str) -> str:
    return hashlib.sha256(key.encode()).hexdigest()[:24]


_STATUS_RANK = {
    "closing_soon": 70,
    "open": 60,
    "new": 55,
    "announced": 50,
    "detected": 40,
    "tracked": 35,
    "closed": 10,
}


def _richness(item: dict) -> tuple[int, int, int]:
    useful = (
        "title", "organization", "city", "uf", "region", "scope", "type",
        "education", "area", "remuneration", "vacancies", "fee", "url",
        "edital_url", "notice_number", "year",
    )
    populated = sum(1 for key in useful if item.get(key) not in (None, "", [], {}))
    text_size = sum(len(str(item.get(key) or "")) for key in useful)
    return populated, text_size, int(item.get("priority") or 0)


def _earliest(values: list[str]) -> str:
    present = [value for value in values if value]
    return min(present) if present else ""


def _latest(values: list[str]) -> str:
    present = [value for value in values if value]
    return max(present) if present else ""


def collapse_items(items: list[dict]) -> list[dict]:
    """Collapse canonical-ID collisions while preserving the richest trustworthy row."""
    grouped: dict[str, list[dict]] = {}
    order: list[str] = []
    for raw in items:
        item = dict(raw)
        item_id = str(item.get("id") or "").strip()
        if not item_id:
            continue
        if item_id not in grouped:
            grouped[item_id] = []
            order.append(item_id)
        grouped[item_id].append(item)

    collapsed: list[dict] = []
    for item_id in order:
        group = grouped[item_id]
        base = dict(max(group, key=_richness))
        base["id"] = item_id

        keys = set().union(*(item.keys() for item in group))
        for key in keys:
            if key in {"id", "first_seen", "last_seen", "priority", "status", "active"}:
                continue
            candidates = [item.get(key) for item in group if item.get(key) not in (None, "", [], {})]
            if not candidates:
                continue
            current = base.get(key)
            if current in (None, "", [], {}):
                base[key] = max(candidates, key=lambda value: len(str(value)))
            elif isinstance(current, str):
                richer = max(candidates, key=lambda value: len(str(value)))
                if len(str(richer)) > len(current):
                    base[key] = richer

        base["priority"] = max(int(item.get("priority") or 0) for item in group)
        base["status"] = max(
            (str(item.get("status") or "detected") for item in group),
            key=lambda value: _STATUS_RANK.get(value, 0),
        )
        base["active"] = any(item.get("active", True) is not False for item in group)
        base["first_seen"] = _earliest([str(item.get("first_seen") or "") for item in group])
        base["last_seen"] = _latest([str(item.get("last_seen") or "") for item in group])
        collapsed.append(base)
    return collapsed


def recalculate_new_count(items: list[dict], previously_seen: set[str]) -> int:
    current_ids = {str(item.get("id")) for item in items if item.get("id")}
    return len(current_ids - {str(value) for value in previously_seen})


def main() -> int:
    feed = load(FEED, {})
    history = load(HISTORY, {"keys": {}})
    mapping = dict(history.get("keys", {}))
    previous_seen = {str(x) for x in feed.get("seen_ids", []) if x}
    remapped = 0

    remapped_items = []
    for raw in feed.get("items", []):
        item = dict(raw)
        key = identity_key(item)
        notice, year = notice_identity(item)
        item["notice_number"] = notice
        item["year"] = year
        existing = mapping.get(key)
        if existing:
            if item.get("id") != existing:
                item["id"] = existing
                remapped += 1
        else:
            mapping[key] = str(item.get("id") or generated_id(key))
            item["id"] = mapping[key]
        remapped_items.append(item)

    items = collapse_items(remapped_items)
    current_ids = [str(item.get("id")) for item in items if item.get("id")]
    feed["items"] = items
    feed["new_count"] = recalculate_new_count(items, previous_seen)
    feed["schema_version"] = max(3, int(feed.get("schema_version") or 0))
    feed["seen_ids"] = list(dict.fromkeys([str(x) for x in feed.get("seen_ids", [])] + current_ids))[-5000:]
    save(FEED, feed)
    save(HISTORY, {"schema_version": 1, "keys": mapping})
    print(
        f"[IDENTITY] itens={len(items)} remapeados={remapped} "
        f"identidades={len(mapping)} novos={feed['new_count']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
