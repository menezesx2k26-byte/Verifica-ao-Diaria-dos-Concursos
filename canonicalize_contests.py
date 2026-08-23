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


def main() -> int:
    feed = load(FEED, {})
    history = load(HISTORY, {"keys": {}})
    mapping = dict(history.get("keys", {}))
    current_ids = {str(x.get("id")) for x in feed.get("items", []) if x.get("id")}
    remapped = 0

    for item in feed.get("items", []):
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
            # Preserve existing deployed IDs on first contact; use the canonical identity thereafter.
            mapping[key] = str(item.get("id") or generated_id(key))
            item["id"] = mapping[key]
        current_ids.add(item["id"])

    feed["schema_version"] = max(3, int(feed.get("schema_version") or 0))
    feed["seen_ids"] = list(dict.fromkeys([str(x) for x in feed.get("seen_ids", [])] + [str(x.get("id")) for x in feed.get("items", []) if x.get("id")]))[-5000:]
    save(FEED, feed)
    save(HISTORY, {"schema_version": 1, "keys": mapping})
    print(f"[IDENTITY] itens={len(feed.get('items', []))} remapeados={remapped} identidades={len(mapping)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
