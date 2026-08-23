#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import io
import json
import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup
from pypdf import PdfReader

STATE = Path("state/priority_events.json")
TIMEOUT = 30


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def fold(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c)).casefold()
    return re.sub(r"\s+", " ", s).strip()


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def stable(*parts: str) -> str:
    return hashlib.sha256("|".join(parts).encode()).hexdigest()[:24]


def load() -> dict:
    try:
        return json.loads(STATE.read_text(encoding="utf-8"))
    except Exception:
        return {"documents": [], "events": [], "source_health": []}


def save(obj: dict) -> None:
    STATE.parent.mkdir(exist_ok=True)
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(STATE)


@dataclass(frozen=True)
class Watch:
    id: str
    label: str
    contest_id: str
    urls: tuple[str, ...]
    groups: tuple[tuple[str, ...], ...]
    expected_on_specific_page: bool = False


WATCHES = (
    Watch(
        "pg_acs_004_2024", "Praia Grande — ACS 004/2024", "pg-004-2024-acs",
        (
            "https://www.praiagrande.sp.gov.br/administracao/concurso_publico.asp?cd_pagina=187",
            "https://plenussistemas.dioenet.com.br/list/praia-grande",
        ),
        (("004/2024", "004-2024", "004 2024"), ("agente comunitario de saude", " acs ")),
    ),
    Watch(
        "sv_atg_02_2026", "São Vicente — Assistente-Técnico de Gestão 02/2026", "sv-02-2026-atg",
        (
            "https://www.saovicente.sp.gov.br/institucional/concursos/concurso-no-02-2026",
            "https://www.saovicente.sp.gov.br/transparencia/bom",
            "https://www.ibamsp-concursos.org.br/informacoes/134/",
        ),
        (("02/2026", "02-2026", "concurso no 02/2026"), ("assistente-tecnico de gestao", "assistente técnico de gestão", "assistente tecnico de gestao")),
        True,
    ),
    Watch(
        "pg_math_002_2025", "Praia Grande — Professor III Matemática 002/2025", "pg-002-2025-prof-mat",
        (
            "https://www.praiagrande.sp.gov.br/administracao/concurso_publico.asp?cd_pagina=187",
            "https://plenussistemas.dioenet.com.br/list/praia-grande",
        ),
        (("002/2025", "002-2025", "002 2025"), ("professor iii",), ("matematica", "matemática")),
    ),
)

EVENT_TERMS = (
    ("appointment", 120, ("nomeacao", "nomeação", "nomeado", "portaria de nomeacao")),
    ("convocation", 115, ("convocacao", "convocação", "convocado", "chamamento")),
    ("possession", 115, ("posse", "admissao", "admissão", "exame admissional", "entrega de documentos")),
    ("class_final", 100, ("classificacao final", "classificação final", "resultado final")),
    ("homologation", 100, ("homologacao", "homologação")),
    ("reclassification", 105, ("reclassificacao", "reclassificação", "desistencia", "desistência")),
    ("lesson_assignment", 110, ("atribuicao de aulas", "atribuição de aulas", "atribuicao de classe", "atribuição de classe")),
    ("rectification", 75, ("retificacao", "retificação")),
)


def groups_match(text: str, groups: tuple[tuple[str, ...], ...]) -> bool:
    t = f" {fold(text)} "
    return all(any(fold(term) in t for term in group) for group in groups)


def extract_pdf(data: bytes) -> str:
    reader = PdfReader(io.BytesIO(data))
    chunks = []
    for page in reader.pages[:80]:
        chunks.append(page.extract_text() or "")
    return re.sub(r"\s+", " ", " ".join(chunks)).strip()


def fetch_document(url: str) -> tuple[bytes, str, str]:
    r = requests.get(url, timeout=TIMEOUT, headers={"User-Agent": "ConcursosWatch-Priority/3.0", "Cache-Control": "no-cache"}, allow_redirects=True)
    r.raise_for_status()
    data = r.content
    ctype = (r.headers.get("content-type") or "").lower()
    is_pdf = "pdf" in ctype or urlparse(r.url).path.lower().endswith(".pdf") or data[:4] == b"%PDF"
    if is_pdf:
        return data, extract_pdf(data), "pdf"
    soup = BeautifulSoup(data, "html.parser")
    for n in soup(["script", "style", "noscript", "svg"]): n.decompose()
    return data, " ".join(soup.stripped_strings), "html"


def candidate_links(base_url: str, html: str, watch: Watch) -> list[tuple[str, str]]:
    soup = BeautifulSoup(html, "html.parser")
    out: list[tuple[str, str]] = []
    seen = set()
    for a in soup.find_all("a", href=True):
        title = " ".join(a.stripped_strings)
        parent = " ".join(a.parent.stripped_strings) if a.parent else title
        context = f"{title} {parent}"
        href = urljoin(base_url, a.get("href"))
        if groups_match(context + " " + href, watch.groups) and href not in seen:
            seen.add(href); out.append((href, title or parent[:180]))
    return out


def detect_events(watch: Watch, source_id: str, text: str, url: str, doc_hash: str, stamp: str) -> list[dict]:
    t = fold(text)
    out = []
    for event_type, priority, terms in EVENT_TERMS:
        matches = [fold(x) for x in terms if fold(x) in t]
        if not matches: continue
        # target identity must still be present in the inspected document/context
        if not groups_match(text, watch.groups): continue
        fingerprint = stable(watch.contest_id, event_type, doc_hash, ",".join(sorted(matches)))
        sample_pos = min((t.find(x) for x in matches if t.find(x) >= 0), default=0)
        excerpt = t[max(0, sample_pos - 350):sample_pos + 900]
        out.append({
            "id": stable("event", fingerprint), "contest_id": watch.contest_id, "source_id": source_id,
            "type": event_type, "title": f"{watch.label} — {event_type}", "body": excerpt,
            "url": url, "priority": priority, "happened_at": stamp, "created_at": stamp, "fingerprint": fingerprint,
        })
    return out


def scan() -> dict:
    previous = load()
    old_docs = {d.get("id"): d for d in previous.get("documents", []) if d.get("id")}
    old_events = {e.get("fingerprint"): e for e in previous.get("events", []) if e.get("fingerprint")}
    documents: dict[str, dict] = dict(old_docs)
    events: dict[str, dict] = dict(old_events)
    health = []
    stamp = now()
    new_events = []

    for watch in WATCHES:
        for idx, source_url in enumerate(watch.urls):
            source_id = f"{watch.id}:{idx}"
            try:
                data, text, kind = fetch_document(source_url)
                fingerprint = sha(data)
                parser_ok = len(fold(text)) > 100
                semantic_ok = parser_ok and (groups_match(text, watch.groups) if (idx == 0 and watch.expected_on_specific_page) else True)
                links = candidate_links(source_url, text if kind == "html" else "", watch) if kind == "html" else []
                inspected = [(source_url, watch.label, data, text, kind)]
                for href, title in links[:20]:
                    try:
                        d, tx, kd = fetch_document(href)
                        inspected.append((href, title, d, tx, kd))
                    except Exception:
                        continue

                changed = False
                for href, title, d, tx, kd in inspected:
                    h = sha(d); did = stable(source_id, href)
                    old = documents.get(did)
                    if not old or old.get("sha256") != h: changed = True
                    documents[did] = {
                        "id": did, "contest_id": watch.contest_id, "source_id": source_id, "kind": kd,
                        "title": title[:220], "url": href, "sha256": h, "published_at": "", "fetched_at": stamp,
                        "text_excerpt": re.sub(r"\s+", " ", tx)[:12000], "metadata": {"watch": watch.id},
                    }
                    for event in detect_events(watch, source_id, tx, href, h, stamp):
                        if event["fingerprint"] not in events:
                            new_events.append(event)
                        events[event["fingerprint"]] = event

                scan_status = "NEW_EVENT" if any(e["source_id"] == source_id for e in new_events) else ("SOURCE_CHANGED" if changed else "NO_CHANGE_CONFIRMED")
                health.append({
                    "id": source_id, "label": f"{watch.label} — fonte {idx+1}", "url": source_url,
                    "http_ok": True, "parser_ok": parser_ok, "semantic_ok": semantic_ok,
                    "item_count": len(inspected), "expected_min": 1, "checked_at": stamp,
                    "last_success_at": stamp if semantic_ok else "", "fingerprint": fingerprint,
                    "scan_status": scan_status if semantic_ok else "PARSER_ERROR", "error": "" if semantic_ok else "conteúdo esperado não confirmado",
                })
            except Exception as exc:
                health.append({
                    "id": source_id, "label": f"{watch.label} — fonte {idx+1}", "url": source_url,
                    "http_ok": False, "parser_ok": False, "semantic_ok": False, "item_count": 0,
                    "expected_min": 1, "checked_at": stamp, "last_success_at": "", "fingerprint": "",
                    "scan_status": "SOURCE_ERROR", "error": str(exc)[:500],
                })

    result = {
        "schema_version": 3, "updated_at": stamp,
        "documents": sorted(documents.values(), key=lambda x: x.get("fetched_at", ""), reverse=True)[-1500:],
        "events": sorted(events.values(), key=lambda x: x.get("created_at", ""), reverse=True)[-1500:],
        "new_events": new_events, "source_health": health,
    }
    save(result)
    print(f"[PRIORITY] fontes={len(health)} novos_eventos={len(new_events)} documentos={len(result['documents'])}")
    return result


if __name__ == "__main__":
    scan()
