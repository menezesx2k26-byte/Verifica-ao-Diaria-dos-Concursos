#!/usr/bin/env python3
"""Recurring deep audit for documents that may be silently replaced at the same URL.

The deep audit is an infrastructure/audit layer. Generic fingerprint changes are
kept in logs/state and MUST NOT become user-facing notifications. Only a change
that matches a private priority term is promoted to the alert channels.
"""
from __future__ import annotations

import html as html_lib
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urljoin, urlparse, urlunparse

import monitor_runner as transports
import monitor

DEEP_STATE = Path(os.getenv("WATCH_DEEP_STATE", "state/deep.json"))
SEEDS_CONFIG = Path(os.getenv("WATCH_DEEP_SEEDS", "config/deep_seeds.json"))
DEFAULT_PER_SOURCE = 6
MAX_EVENTS = 10
AUDIT_VERSION = 7

SEMANTIC_TERMS: dict[str, list[str]] = {
    "assistente_tecnico_gestao": ["assistente-técnico de gestão", "assistente tecnico de gestao"],
    "agente_comunitario_saude": ["agente comunitário de saúde", "agente comunitario de saude"],
    "concurso_02_2026": ["02/2026", "02-2026", "concurso público nº 02/2026", "concurso publico n 02/2026"],
    "concurso_004_2024": ["004/2024", "004-2024", "004 2024"],
    "classificacao_final": ["classificação final", "classificacao final"],
    "homologacao": ["homolog"],
    "convocacao": ["convocação", "convocacao", "convocado", "convocada"],
    "nomeacao": ["nomeação", "nomeacao", "nomeado", "nomeada"],
    "posse": ["posse"],
    "reclassificacao": ["reclassificação", "reclassificacao", "reclassificado", "reclassificada"],
}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _safe_pdf_target(raw: str) -> str | None:
    candidate = html_lib.unescape((raw or "").strip())
    parsed = urlparse(candidate)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return None
    if not parsed.path.casefold().endswith(".pdf"):
        return None
    return candidate


def audit_content_url(url: str) -> str:
    parsed = urlparse(url)
    path = parsed.path
    if path.casefold().endswith(".pdf/view"):
        return urlunparse(parsed._replace(path=path[:-5]))
    if path.casefold().endswith("/viewer.php") or path.casefold().endswith("viewer.php"):
        for raw in parse_qs(parsed.query).get("file", []):
            target = _safe_pdf_target(raw)
            if target:
                return target
    return url


def embedded_document_urls(html: str, base_url: str) -> list[str]:
    values = re.findall(r"(?is)<(?:iframe|embed)\b[^>]*?\bsrc\s*=\s*['\"]([^'\"]+)['\"]", html)
    out: list[str] = []
    seen: set[str] = set()
    for raw in values:
        absolute = urljoin(base_url, html_lib.unescape(raw.strip()))
        parsed = urlparse(absolute)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            continue
        if absolute not in seen:
            seen.add(absolute)
            out.append(absolute)
    return out[:4]


def response_text(r, requested_url: str) -> str:
    ctype = (r.headers.get("content-type") or "").casefold()
    effective = (getattr(r, "url", None) or requested_url).casefold().split("?")[0]
    if "pdf" in ctype or effective.endswith(".pdf"):
        return monitor.pdf_text(r.content)
    text, _ = monitor.page_data(r.text, getattr(r, "url", requested_url))
    return text


def extract_document_text(url: str) -> tuple[str, str]:
    target = audit_content_url(url)
    try:
        r = monitor.fetch(target, binary=True)
        text = response_text(r, target)
        ctype = (r.headers.get("content-type") or "").casefold()
        if "html" in ctype and hasattr(r, "text"):
            base_text_len = len(monitor.fold(text))
            for embedded in embedded_document_urls(r.text, getattr(r, "url", target)):
                try:
                    embedded_target = audit_content_url(embedded)
                    er = monitor.fetch(embedded_target, binary=True)
                    candidate = response_text(er, embedded_target)
                    candidate_len = len(monitor.fold(candidate))
                    if candidate_len >= 80 and candidate_len > base_text_len:
                        text = candidate
                        target = getattr(er, "url", embedded_target) or embedded_target
                        base_text_len = candidate_len
                except Exception as exc:
                    print(f"[DEEP] embed ignorado {embedded}: {exc}")
        if len(monitor.fold(text)) >= 80:
            return text, target
    except Exception:
        if not transports._allowed(url):
            raise
    if transports._allowed(url):
        text = transports._reader_markdown(url)
        if len(monitor.fold(text)) >= 80:
            return text, url
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


def semantic_facts(text: str, source: dict[str, Any]) -> dict[str, bool]:
    normalized = monitor.fold(text)
    facts = {key: any(monitor.fold(term) in normalized for term in terms) for key, terms in SEMANTIC_TERMS.items()}
    matched, priority, _ = monitor.document_result(text, source)
    facts["source_match"] = bool(matched)
    facts["priority_match"] = bool(priority)
    return facts


def selected_documents(source: dict[str, Any], live: dict[str, Any], seeds: dict[str, Any]) -> list[dict[str, Any]]:
    sid = source["id"]
    count = int(source.get("deep_audit_count", DEFAULT_PER_SOURCE))
    live_urls = list((live.get(sid) or {}).get("document_urls") or [])[-max(1, count):]
    docs: dict[str, dict[str, Any]] = {url: {"url": url, "label": "documento conhecido", "seeded": False} for url in live_urls}
    for item in ((seeds.get("sources") or {}).get(sid) or []):
        url = str(item.get("url") or "").strip()
        if url:
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
    priority_events: list[tuple[str, str]] = []
    failures = 0
    seeded_count = 0
    suppressed_changes = 0

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
                text, content_url = extract_document_text(url)
                normalized = monitor.fold(text)
                signature = monitor.digest(normalized)
                facts = semantic_facts(text, source)
                entry = {
                    "signature": signature,
                    "checked_at": now_iso(),
                    "chars": len(normalized),
                    "failures": 0,
                    "label": doc.get("label"),
                    "stage": doc.get("stage"),
                    "seeded": bool(doc.get("seeded")),
                    "semantic": facts,
                }
                if content_url != url:
                    entry["content_url"] = content_url
                new_docs[url] = entry

                previous = old.get("signature")
                if previous and previous != signature:
                    matched, priority, snippet = monitor.document_result(text, source)
                    if matched and priority:
                        priority_events.append((
                            f"PRIORIDADE — {source['label']}",
                            "🔁 Documento prioritário alterado sem mudança de URL.\n\n"
                            f"Documento: {doc.get('label') or '(sem rótulo)'}\n"
                            f"Estágio: {doc.get('stage') or 'não informado'}\n\n"
                            f"{snippet[:1700]}\n\nURL: {url}\nFonte índice: {source['url']}",
                        ))
                    elif matched:
                        suppressed_changes += 1
                        print(f"[DEEP] mudança relevante para auditoria, mas suprimida do usuário: {url}")
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
                    print(f"[DEEP] auditoria indisponível (mantido só em log): {url} — {exc}")

        deep[sid] = {"label": source["label"], "documents": new_docs, "checked_at": now_iso()}

    deep["_meta"] = {
        "audit_version": AUDIT_VERSION,
        "checked_at": now_iso(),
        "sources": len([k for k in deep if not k.startswith("_")]),
        "seeded_documents_checked": seeded_count,
        "failures": failures,
        "suppressed_user_alerts": suppressed_changes,
    }
    save(DEEP_STATE, deep)

    for subject, body in priority_events[:MAX_EVENTS]:
        eid = monitor.event_id(subject, body)
        monitor.notify(f"[CONCURSOS][DEEP-{eid}] {subject}", body, f"deep-{eid}")

    print(f"[DEEP] concluído: prioridades={len(priority_events)} suprimidos={suppressed_changes} falhas={failures} seeds={seeded_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
