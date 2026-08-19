#!/usr/bin/env python3
"""Recurring deep audit for documents that may be silently replaced at the same URL.

The frequent monitor is optimized for new links and relevant page contexts. This
second pass deliberately downloads only a small tail of already-known documents
plus a tiny set of critical seeded documents and fingerprints their normalized
textual content. It catches the important edge case where a municipality/bank
edits or replaces a PDF without changing its URL.
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

# Importing the wrapper installs direct->Reader fallback and augmented alerts.
import monitor_runner as transports
import monitor

DEEP_STATE = Path(os.getenv("WATCH_DEEP_STATE", "state/deep.json"))
SEEDS_CONFIG = Path(os.getenv("WATCH_DEEP_SEEDS", "config/deep_seeds.json"))
DEFAULT_PER_SOURCE = 6
MAX_EVENTS = 10
AUDIT_VERSION = 3


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def extract_document_text(url: str) -> str:
    try:
        r = monitor.fetch(url, binary=True)
        ctype = (r.headers.get("content-type") or "").casefold()
        if "pdf" in ctype or url.casefold().split("?")[0].endswith(".pdf"):
            text = monitor.pdf_text(r.content)
        else:
            text, _ = monitor.page_data(r.text, r.url)
        if len(monitor.fold(text)) >= 80:
            return text
    except Exception:
        if not transports._allowed(url):
            raise

    if transports._allowed(url):
        text = transports._reader_markdown(url)
        if len(monitor.fold(text)) >= 80:
            return text
    raise RuntimeError("documento sem texto suficiente para fingerprint")


def load(path: Path, default: Any) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def save(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def selected_documents(
    source: dict[str, Any],
    live: dict[str, Any],
    seeds: dict[str, Any],
) -> list[dict[str, Any]]:
    """Return a bounded live tail plus all explicit critical seeds, deduplicated by URL."""
    sid = source["id"]
    count = int(source.get("deep_audit_count", DEFAULT_PER_SOURCE))
    live_urls = list((live.get(sid) or {}).get("document_urls") or [])[-max(1, count):]

    docs: dict[str, dict[str, Any]] = {
        url: {"url": url, "label": "documento conhecido", "seeded": False}
        for url in live_urls
    }
    for item in ((seeds.get("sources") or {}).get(sid) or []):
        url = str(item.get("url") or "").strip()
        if not url:
            continue
        docs[url] = {
            "url": url,
            "label": str(item.get("label") or "documento crítico").strip(),
            "stage": str(item.get("stage") or "").strip() or None,
            "seeded": True,
        }
    return list(docs.values())


def main() -> int:
    cfg = load(monitor.CONFIG, {})
    live = load(monitor.STATE, {})
    seeds = load(SEEDS_CONFIG, {})
    deep = load(DEEP_STATE, {})
    events: list[tuple[str, str]] = []
    failures = 0
    seeded_count = 0

    for source in cfg.get("sources", []):
        sid = source["id"]
        selected = selected_documents(source, live, seeds)
        if not selected:
            continue

        old_source = deep.get(sid) or {}
        old_docs = old_source.get("documents") or {}
        new_docs: dict[str, Any] = {}

        print(f"[DEEP] {source['label']} — {len(selected)} documento(s)")
        for doc in selected:
            url = doc["url"]
            old = old_docs.get(url) or {}
            if doc.get("seeded"):
                seeded_count += 1
            try:
                text = extract_document_text(url)
                normalized = monitor.fold(text)
                signature = monitor.digest(normalized)
                new_docs[url] = {
                    "signature": signature,
                    "checked_at": now_iso(),
                    "chars": len(normalized),
                    "failures": 0,
                    "label": doc.get("label"),
                    "stage": doc.get("stage"),
                    "seeded": bool(doc.get("seeded")),
                }

                previous = old.get("signature")
                if previous and previous != signature:
                    matched, priority, snippet = monitor.document_result(text, source)
                    if matched:
                        prefix = "PRIORIDADE" if priority else "DOCUMENTO ALTERADO"
                        events.append((
                            f"{prefix} — {source['label']}",
                            "🔁 Um documento já conhecido mudou de conteúdo sem mudar de URL.\n\n"
                            f"Documento: {doc.get('label') or '(sem rótulo)'}\n"
                            f"Estágio: {doc.get('stage') or 'não informado'}\n\n"
                            f"{snippet[:1700]}\n\nURL: {url}\nFonte índice: {source['url']}",
                        ))
                    else:
                        print(f"[DEEP] fingerprint mudou, mas sem correspondência atual: {url}")
            except Exception as exc:
                failures += 1
                n = min(int(old.get("failures", 0)) + 1, 3)
                new_docs[url] = {
                    **old,
                    "checked_at": now_iso(),
                    "failures": n,
                    "last_error": str(exc)[:700],
                    "label": doc.get("label"),
                    "stage": doc.get("stage"),
                    "seeded": bool(doc.get("seeded")),
                }
                if n == 3 and int(old.get("failures", 0)) < 3:
                    events.append((
                        f"DEEP AUDIT INDISPONÍVEL — {source['label']}",
                        "⚠️ O mesmo documento falhou em 3 auditorias profundas.\n"
                        f"Documento: {doc.get('label') or '(sem rótulo)'}\n"
                        f"URL: {url}\nErro: {exc}",
                    ))

        deep[sid] = {
            "label": source["label"],
            "documents": new_docs,
            "checked_at": now_iso(),
        }

    deep["_meta"] = {
        "audit_version": AUDIT_VERSION,
        "checked_at": now_iso(),
        "sources": len([k for k in deep if not k.startswith("_")]),
        "seeded_documents_checked": seeded_count,
        "failures": failures,
    }
    save(DEEP_STATE, deep)

    for subject, body in events[:MAX_EVENTS]:
        eid = monitor.event_id(subject, body)
        monitor.notify(f"[CONCURSOS][DEEP-{eid}] {subject}", body, f"deep-{eid}")

    print(
        f"[DEEP] concluído: eventos={len(events)} falhas={failures} "
        f"seeds={seeded_count}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
