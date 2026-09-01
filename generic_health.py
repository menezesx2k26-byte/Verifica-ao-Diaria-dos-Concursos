#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

import requests
from bs4 import BeautifulSoup

CONFIG = Path("config/new_contests_sources.json")
FEED = Path("state/new_contests.json")
HISTORY = Path("state/source_health_history.json")
TIMEOUT = 25


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


def clean_text(html: str) -> tuple[str, int]:
    soup = BeautifulSoup(html, "html.parser")
    for node in soup(["script", "style", "noscript", "svg"]):
        node.decompose()
    anchors = sum(1 for a in soup.find_all("a", href=True) if (a.get("href") or "").strip())
    text = re.sub(r"\s+", " ", " ".join(soup.stripped_strings)).strip()
    return text, anchors


def fingerprint(text: str) -> str:
    normalized = re.sub(r"\s+", " ", text).strip().lower()
    return hashlib.sha256(normalized.encode()).hexdigest()


def main() -> int:
    cfg = load(CONFIG, {})
    feed = load(FEED, {})
    history = load(HISTORY, {})
    prior = history.get("sources", {})
    item_counts = {x.get("id"): int(x.get("item_count") or 0) for x in feed.get("source_health", []) if x.get("id")}
    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    rows = []
    next_history = {}

    for source in cfg.get("sources", []):
        sid = source.get("id", "")
        previous = prior.get(sid, {})
        current_items = item_counts.get(sid, 0)
        expected_min = int(source.get("expected_min") or previous.get("expected_min") or 0)
        try:
            r = requests.get(source["url"], timeout=TIMEOUT, headers={"User-Agent":"ConcursosWatch-Health/3.0","Cache-Control":"no-cache"}, allow_redirects=True)
            r.raise_for_status()
            text, anchors = clean_text(r.text)
            length = len(text)
            parser_ok = length >= 200 and anchors >= 2
            prev_len = int(previous.get("text_length") or 0)
            prev_items = int(previous.get("item_count") or 0)
            collapse = (prev_len >= 1000 and length < max(200, int(prev_len * 0.20))) or (prev_items >= 3 and current_items == 0)
            semantic_ok = parser_ok and not collapse and (current_items >= expected_min if expected_min > 0 else True)
            fp = fingerprint(text)
            old_fp = previous.get("fingerprint", "")
            status = "NO_CHANGE_CONFIRMED" if semantic_ok and old_fp == fp else ("SOURCE_CHANGED" if semantic_ok else "PARSER_ERROR")
            error = ""
            if collapse: error = "anomalia estrutural: conteúdo/itens caiu abruptamente em relação à última coleta válida"
            elif not parser_ok: error = "HTML respondeu, mas a estrutura não passou no mínimo de texto/links"
            elif expected_min and current_items < expected_min: error = f"itens extraídos {current_items} abaixo do mínimo esperado {expected_min}"
            last_success = stamp if semantic_ok else previous.get("last_success_at", "")
            row = {
                "id": sid, "label": source.get("label", sid), "url": r.url,
                "http_ok": True, "parser_ok": parser_ok, "semantic_ok": semantic_ok,
                "item_count": current_items, "expected_min": expected_min, "checked_at": stamp,
                "last_success_at": last_success, "fingerprint": fp, "scan_status": status, "error": error,
            }
            next_history[sid] = {
                "fingerprint": fp if semantic_ok else previous.get("fingerprint", ""),
                "text_length": length if semantic_ok else previous.get("text_length", 0),
                "item_count": current_items if semantic_ok else previous.get("item_count", 0),
                "expected_min": expected_min,
                "last_success_at": last_success,
            }
        except Exception as exc:
            row = {
                "id": sid, "label": source.get("label", sid), "url": source.get("url", ""),
                "http_ok": False, "parser_ok": False, "semantic_ok": False,
                "item_count": current_items, "expected_min": expected_min, "checked_at": stamp,
                "last_success_at": previous.get("last_success_at", ""), "fingerprint": previous.get("fingerprint", ""),
                "scan_status": "SOURCE_ERROR", "error": str(exc)[:500],
            }
            next_history[sid] = previous
        rows.append(row)

    feed["schema_version"] = max(int(feed.get("schema_version") or 0), 3)
    feed["source_health"] = rows
    feed["healthy_source_count"] = sum(1 for x in rows if x["http_ok"] and x["parser_ok"] and x["semantic_ok"])
    save(FEED, feed)
    save(HISTORY, {"updated_at": stamp, "sources": next_history})
    print(f"[HEALTH] semanticamente saudáveis={feed['healthy_source_count']}/{len(rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
