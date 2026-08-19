#!/usr/bin/env python3
"""Auxiliary discovery sensor for sources that block a GitHub runner.

This probe is intentionally *not* an authority. It searches the public web index
for pages on the official IBAM URL and reports newly indexed critical contexts as
"discovery" events that still require confirmation from IBAM/PMSV/BOM.

The frequent direct monitor remains the source-of-truth path. This sensor exists
to reduce blind time while an origin WAF blocks GitHub Actions.
"""
from __future__ import annotations

import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests

# Installs resilient alert fan-out (including optional Gmail SMTP).
import monitor_runner  # noqa: F401
import monitor

STATE = Path(os.getenv("WATCH_SEARCH_STATE", "state/search.json"))
SEARCH_ENDPOINT = "https://s.jina.ai/"
FAILURE_THRESHOLD = 6
MAX_ALERTS = 6

PROBES = [
    {
        "id": "ibam_sv_02_2026_index",
        "label": "IBAM São Vicente 02/2026 — índice web auxiliar",
        "query": (
            'site:ibamsp-concursos.org.br/informacoes/134/ '
            '"SÃO VICENTE" "02/2026" '
            'classificação final homologação convocação nomeação posse'
        ),
        "official_url": "https://www.ibamsp-concursos.org.br/informacoes/134/",
        "official_marker": "ibamsp-concursos.org.br/informacoes/134",
        "required_groups": [
            ["são vicente", "sao vicente"],
            ["02/2026", "02-2026"],
        ],
        "triggers": [
            "classificação final",
            "classificacao final",
            "homologação",
            "homologacao",
            "convocação",
            "convocacao",
            "nomeação",
            "nomeacao",
            "posse",
            "resultado de recurso",
            "resultado do recurso",
            "despacho",
        ],
    }
]


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


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


def official_sections(text: str, marker: str) -> list[str]:
    """Keep only search-result sections that point back to the official page."""
    # Reader/Search commonly emits one Title/URL/Content block per result. Keep
    # the parser tolerant to formatting changes and never accept a section from
    # an unrelated domain merely because it repeats the contest name.
    sections = re.split(r"(?im)(?=^\s*(?:Title|Título):\s*)", text)
    selected = [section for section in sections if marker.casefold() in section.casefold()]
    if selected:
        return selected
    if marker.casefold() in text.casefold():
        return [text]
    return []


def relevant_contexts(text: str, probe: dict[str, Any]) -> list[str]:
    pterms = monitor.priority_terms()
    terms = list(dict.fromkeys(list(probe["triggers"]) + pterms))
    contexts = monitor.contexts(text, terms, radius=1300)
    out: list[str] = []
    for ctx in contexts:
        if not monitor.groups_match(ctx, probe["required_groups"]):
            continue
        if not monitor.any_match(ctx, probe["triggers"] + pterms):
            continue
        out.append(ctx)
    return sorted(set(out))


def search_web(query: str, api_key: str) -> str:
    r = requests.get(
        SEARCH_ENDPOINT,
        params={"q": query},
        headers={
            "Authorization": f"Bearer {api_key}",
            "Accept": "text/plain",
            "User-Agent": "ConcursosWatch-SearchProbe/1.0",
        },
        timeout=50,
    )
    r.raise_for_status()
    text = r.text
    if len(monitor.fold(text)) < 200:
        raise RuntimeError("índice web devolveu conteúdo insuficiente")
    return text


def run_probe(probe: dict[str, Any], old: dict[str, Any], api_key: str):
    raw = search_web(probe["query"], api_key)
    sections = official_sections(raw, probe["official_marker"])
    if not sections:
        raise RuntimeError("resultado não contém a URL oficial esperada")

    official_text = "\n\n".join(sections)
    if not monitor.groups_match(official_text, probe["required_groups"]):
        raise RuntimeError("resultado não comprovou identidade São Vicente + 02/2026")

    ctxs = relevant_contexts(official_text, probe)
    entries = [{"sig": monitor.digest(ctx), "context": ctx} for ctx in ctxs]
    entries.sort(key=lambda item: item["sig"])
    sigs = [item["sig"] for item in entries]

    events: list[tuple[str, str]] = []
    if "context_sigs" in old:
        previous = set(old.get("context_sigs") or [])
        added = [item for item in entries if item["sig"] not in previous]
        if added:
            pterms = monitor.priority_terms()
            priority = bool(pterms) and any(
                monitor.any_match(item["context"], pterms) for item in added
            )
            sample = "\n\n".join(f"• {item['context'][:1300]}" for item in added[:3])
            prefix = "PRIORIDADE — " if priority else "DESCOBERTA — "
            events.append((
                f"{prefix}{probe['label']}",
                "🔎 Um índice web auxiliar encontrou contexto novo ligado à página oficial do IBAM.\n\n"
                f"{sample}\n\n"
                "⚠️ Este canal é apenas descoberta e NÃO confirma o ato. Confirme no IBAM/PMSV/BOM.\n"
                f"Página oficial monitorada: {probe['official_url']}",
            ))

    new = {
        "label": probe["label"],
        "official_url": probe["official_url"],
        "context_sigs": sigs,
        "context_count": len(sigs),
        "failures": 0,
        "last_ok": now_iso(),
        "provider": "jina-search",
    }
    if int(old.get("failures", 0)) >= FAILURE_THRESHOLD:
        events.append((
            f"RECUPERADO — {probe['label']}",
            "✅ O sensor auxiliar de índice web voltou a responder. O monitor oficial direto continua independente.",
        ))
    return new, events


def main() -> int:
    api_key = (os.getenv("JINA_API_KEY") or "").strip()
    if not api_key:
        print("[SEARCH-PROBE] JINA_API_KEY ausente; sensor auxiliar desativado sem afetar monitores oficiais.")
        return 0

    state = load(STATE, {})
    events: list[tuple[str, str]] = []

    for probe in PROBES:
        old = state.get(probe["id"], {})
        try:
            new, found = run_probe(probe, old, api_key)
            state[probe["id"]] = new
            events.extend(found)
            print(f"[SEARCH-PROBE] OK {probe['label']} contexts={new['context_count']}")
        except Exception as exc:
            prev = int(old.get("failures", 0))
            failures = min(prev + 1, FAILURE_THRESHOLD)
            state[probe["id"]] = {
                **old,
                "label": probe["label"],
                "official_url": probe["official_url"],
                "provider": "jina-search",
                "failures": failures,
                "last_error": str(exc)[:1000],
                "last_failure": now_iso(),
            }
            print(f"[SEARCH-PROBE] falha {probe['label']}: {exc}")
            if failures == FAILURE_THRESHOLD and prev < FAILURE_THRESHOLD:
                events.append((
                    f"SENSOR AUXILIAR INDISPONÍVEL — {probe['label']}",
                    "⚠️ O índice web auxiliar falhou 6 verificações seguidas. Isso NÃO significa que o concurso está fora do ar; "
                    f"apenas a camada de descoberta extra degradou.\nErro: {exc}",
                ))

    state["_meta"] = {"checked_at": now_iso(), "provider": "jina-search"}
    save(STATE, state)

    for subject, body in events[:MAX_ALERTS]:
        eid = monitor.event_id(subject, body)
        monitor.notify(f"[CONCURSOS][SEARCH-{eid}] {subject}", body, f"search-{eid}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
