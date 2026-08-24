#!/usr/bin/env python3
from __future__ import annotations

from collections import Counter
import hashlib
import json
import os
import re
import unicodedata
from datetime import date, datetime, timezone
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urljoin, urlparse, urlunparse

import requests
from bs4 import BeautifulSoup

from relevance_filter import ACCEPTED, evaluate_candidate, matches_interest_profile

CONFIG = Path(os.getenv("NEW_CONTESTS_CONFIG", "config/new_contests_sources.json"))
STATE = Path(os.getenv("NEW_CONTESTS_STATE", "state/new_contests.json"))
DIAGNOSTICS = Path(os.getenv("RELEVANCE_DIAGNOSTICS_STATE", "state/relevance_diagnostics.json"))
TIMEOUT = 25
TRACKING_PREFIXES = ("utm_", "fbclid", "gclid")
OFFICIAL_EXTERNAL_HOST_FRAGMENTS = (
    "ibam", "vunesp", "ciee", "gov.br", "fgv", "fundatec", "cebraspe",
    "fcc.org.br", "institutoaocp", "institutoavaliar", "avalia.org.br",
    "objetivas.com.br", "legalleconcursos", "fepese", "fundacaofafipa",
)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def fold(text: str) -> str:
    text = unicodedata.normalize("NFKD", text or "")
    text = "".join(c for c in text if not unicodedata.combining(c)).casefold()
    return re.sub(r"\s+", " ", text).strip()


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


def canonical_url(raw: str) -> str:
    p = urlparse(raw)
    query = [(k, v) for k, v in parse_qsl(p.query, keep_blank_values=True) if not k.casefold().startswith(TRACKING_PREFIXES)]
    path = re.sub(r"//+", "/", p.path or "/")
    return urlunparse((p.scheme.casefold(), p.netloc.casefold(), path.rstrip("/") or "/", "", urlencode(query), ""))


def stable_id(source_id: str, url: str, title: str) -> str:
    base = f"{source_id}|{canonical_url(url)}|{fold(title)[:160]}"
    return hashlib.sha256(base.encode()).hexdigest()[:24]


def fetch(url: str) -> requests.Response:
    r = requests.get(url, headers={
        "User-Agent": "ConcursosWatch-NewContests/2.1",
        "Accept-Language": "pt-BR,pt;q=0.9",
        "Cache-Control": "no-cache",
    }, timeout=TIMEOUT, allow_redirects=True)
    r.raise_for_status()
    return r


def parse_money(text: str, label: str = "") -> str:
    low = fold(text)
    if label and label not in low:
        return ""
    m = re.search(r"R\$\s*[\d.]+(?:,\d{2})?", text, re.I)
    return m.group(0).strip() if m else ""


def parse_vacancies(text: str) -> str:
    low = fold(text)
    m = re.search(r"\b(\d{1,5})\s+(?:vagas?|oportunidades?)\b", low)
    if m:
        return m.group(1)
    return "CR" if "cadastro reserva" in low or re.search(r"\bcr\b", low) else ""


def parse_dates(text: str) -> tuple[str, str]:
    low = fold(text)
    matches = list(re.finditer(r"\b(\d{1,2})[/-](\d{1,2})[/-](\d{4})\b", low))
    dates: list[tuple[int, str]] = []
    for m in matches:
        try:
            d = date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
            dates.append((m.start(), d.isoformat()))
        except ValueError:
            pass
    if not dates:
        return "", ""
    start = ""
    end = ""
    for pos, value in dates:
        before = low[max(0, pos - 45):pos]
        if any(k in before for k in ("ate ", "termin", "encerra", "fim", "final")):
            end = value
        elif any(k in before for k in ("de ", "inicio", "abertura")) and not start:
            start = value
    if not end:
        end = dates[-1][1]
    if not start and len(dates) > 1:
        start = dates[0][1]
    return start, end


def classify(text: str, end_date: str) -> dict:
    low = fold(text)
    if "estagio" in low or "estagiario" in low:
        kind = "estágio"
    elif "professor" in low or "docente" in low:
        kind = "docência"
    elif "processo seletivo" in low or "processo simplificado" in low:
        kind = "processo seletivo"
    elif "concurso" in low:
        kind = "concurso público"
    else:
        kind = "edital"

    if any(x in low for x in ("nivel medio", "ensino medio", "medio completo")):
        education = "nível médio"
    elif any(x in low for x in ("nivel tecnico", "ensino tecnico", "tecnico")):
        education = "nível técnico"
    elif any(x in low for x in ("superior", "graduacao", "licenciatura", "bacharel")):
        education = "nível superior"
    else:
        education = ""

    area = ""
    areas = [
        (("matematica",), "Matemática"),
        (("mecatronica", "automacao"), "Mecatrônica"),
        (("tecnologia da informacao", "informatica", " ti ", "analista de sistemas"), "TI"),
        (("administrativo", "administracao", "assistente tecnico"), "Administrativo"),
        (("professor", "docente"), "Docência"),
        (("laboratorio",), "Laboratório"),
    ]
    for terms, value in areas:
        if any(t in f" {low} " for t in terms):
            area = value
            break

    status = "detected"
    if any(x in low for x in ("inscricoes abertas", "abertura de inscricoes", "prazo de inscricoes", "prorrogacao do prazo de inscricoes")):
        status = "open"
    elif any(x in low for x in ("autorizacao", "comissao", "banca definida", "contratacao de banca")):
        status = "announced"
    if end_date:
        try:
            remaining = (date.fromisoformat(end_date) - date.today()).days
            if remaining < 0:
                status = "closed"
            elif remaining <= 7:
                status = "closing_soon"
            else:
                status = "open"
        except ValueError:
            pass
    return {"type": kind, "education": education, "area": area, "status": status}


def is_stale_candidate(title: str, context: str, href: str, status: str, end_date: str) -> bool:
    """Reject obviously historical/expired records from archive-heavy official pages."""
    if status == "closed":
        return True
    current = date.today().year
    corpus = fold(f"{title} {context} {href}")
    years = [int(y) for y in re.findall(r"\b(19\d{2}|20\d{2})\b", corpus)]
    if years and max(years) < current - 1:
        return True
    short_years = [int(y) for y in re.findall(r"(?:edital[^\n]{0,30}|/|[-_])(\d{2})(?:\D|$)", corpus)]
    interpreted = [2000 + y for y in short_years if 0 <= y <= 99]
    if interpreted and max(interpreted) < current - 1:
        return True
    if end_date:
        try:
            if date.fromisoformat(end_date) < date.today():
                return True
        except ValueError:
            pass
    return False


def source_uf(source: dict) -> str:
    region = (source.get("region") or "").upper()
    if region in {"SP", "SC", "PR", "RS"}:
        return region
    city = fold(source.get("city", ""))
    if "santa catarina" in city:
        return "SC"
    return ""


def candidate_links(source: dict, include_terms: list[str], exclude_terms: list[str]) -> tuple[list[dict], list[dict], str]:
    r = fetch(source["url"])
    soup = BeautifulSoup(r.text, "html.parser")
    for node in soup(["script", "style", "noscript", "svg"]):
        node.decompose()
    source_host = (urlparse(r.url).hostname or "").casefold()
    out: list[dict] = []
    decisions: list[dict] = []
    seen: set[str] = set()

    for a in soup.find_all("a", href=True):
        href = canonical_url(urljoin(r.url, a.get("href")))
        parsed = urlparse(href)
        if parsed.scheme not in {"http", "https"}:
            continue
        title = " ".join(a.stripped_strings).strip()
        parent = " ".join(a.parent.stripped_strings).strip() if a.parent else title
        context = re.sub(r"\s+", " ", f"{title} {parent}").strip()[:1800]
        combined = fold(f"{context} {href}")
        if not any(fold(term) in combined for term in include_terms):
            continue
        explicit_opening = any(x in combined for x in ("edital de abertura", "inscricoes abertas", "abertura de inscricoes", "prazo de inscricoes"))
        if any(fold(term) in combined for term in exclude_terms) and not explicit_opening:
            continue
        host = (parsed.hostname or "").casefold()
        if host != source_host and not any(x in host for x in OFFICIAL_EXTERNAL_HOST_FRAGMENTS):
            continue
        if href in seen:
            continue
        seen.add(href)
        clean_title = title[:240] or parent[:240] or "Novo edital/processo seletivo"

        relevance = evaluate_candidate(clean_title, context, href)
        if relevance.status != ACCEPTED:
            decisions.append({
                "source_id": source["id"],
                "source": source["label"],
                "title": clean_title,
                "url": href,
                "status": relevance.status,
                "reason": relevance.reason,
                "confidence": relevance.confidence,
                "positive_signals": list(relevance.positive_signals),
                "negative_signals": list(relevance.negative_signals),
            })
            continue

        start_date, end_date = parse_dates(context)
        cls = classify(context, end_date)
        if is_stale_candidate(clean_title, context, href, cls["status"], end_date):
            continue
        remuneration = parse_money(context)
        fee = ""
        fee_match = re.search(r"(?:taxa|inscri(?:ç|c)ao)[^R]{0,35}(R\$\s*[\d.]+(?:,\d{2})?)", context, re.I)
        if fee_match:
            fee = fee_match.group(1).strip()
        out.append({
            "id": stable_id(source["id"], href, clean_title),
            "source_id": source["id"],
            "source": source["label"],
            "organization": source["label"].split(" — ")[0],
            "city": source.get("city", ""),
            "uf": source_uf(source),
            "region": source.get("region", ""),
            "scope": source.get("scope", ""),
            "priority": int(source.get("priority", 50)),
            "title": clean_title,
            "url": href,
            "edital_url": href,
            "type": cls["type"],
            "education": cls["education"],
            "area": cls["area"],
            "remuneration": remuneration,
            "vacancies": parse_vacancies(context),
            "fee": fee,
            "start_date": start_date,
            "end_date": end_date,
            "status": cls["status"],
            "relevance_status": relevance.status,
            "relevance_confidence": relevance.confidence,
        })
    return out, decisions, r.url


def ensure_label(repo: str, token: str, name: str, color: str, description: str) -> None:
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    base = f"https://api.github.com/repos/{repo}"
    r = requests.get(f"{base}/labels/{name}", headers=headers, timeout=20)
    if r.status_code == 200:
        return
    if r.status_code != 404:
        r.raise_for_status()
    c = requests.post(f"{base}/labels", headers=headers, json={"name": name, "color": color, "description": description}, timeout=20)
    if c.status_code not in (201, 422):
        c.raise_for_status()


def create_issue(repo: str, token: str, item: dict) -> None:
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    details = [item.get("education"), item.get("remuneration"), f"até {item['end_date']}" if item.get("end_date") else None]
    body = (
        "Novo edital/processo seletivo detectado em fonte oficial.\n\n"
        f"**Órgão:** {item.get('organization') or item['source']}\n"
        f"**Local:** {item.get('city') or 'não informado'}\n"
        f"**Esfera:** {item.get('scope') or 'não classificada'}\n"
        f"**Situação:** {item.get('status')}\n"
        f"**Dados extraídos:** {' • '.join(x for x in details if x) or 'confira o edital'}\n\n"
        f"**Fonte oficial:** {item['url']}\n\n"
        "_Extração automática: a fonte oficial prevalece em caso de divergência._"
    )
    payload = {"title": f"[NOVO CONCURSO] {item['source']} — {item['title']}"[:250], "body": body, "labels": ["new-contest"]}
    assignee = (os.getenv("ALERT_GITHUB_ASSIGNEE") or "").strip()
    if assignee:
        payload["assignees"] = [assignee]
    requests.post(f"https://api.github.com/repos/{repo}/issues", headers=headers, json=payload, timeout=20).raise_for_status()


def main() -> int:
    cfg = load(CONFIG, {})
    old = load(STATE, {})
    include_terms = list(cfg.get("include_terms") or [])
    exclude_terms = list(cfg.get("exclude_terms") or [])
    interest_profile = dict(cfg.get("interest_profile") or {})
    old_items = {x.get("id"): x for x in old.get("items", []) if x.get("id")}
    old_seen = set(old.get("seen_ids", [])) | set(old_items)
    first_run = not bool(old_seen)
    stamp = now_iso()

    found: dict[str, dict] = {}
    health: list[dict] = []
    failures: list[dict] = []
    all_decisions: list[dict] = []
    filtered_interest = 0
    for source in cfg.get("sources", []):
        try:
            items, decisions, final_url = candidate_links(source, include_terms, exclude_terms)
            all_decisions.extend(decisions)
            for item in items:
                if not matches_interest_profile(item, interest_profile):
                    filtered_interest += 1
                    continue
                found[item["id"]] = item
            health.append({
                "id": source["id"],
                "label": source["label"],
                "ok": True,
                "item_count": len(items),
                "checked_at": stamp,
                "url": final_url,
                "error": "",
            })
        except Exception as exc:
            err = str(exc)[:500]
            failures.append({"source": source.get("label"), "error": err})
            health.append({"id": source.get("id", ""), "label": source.get("label", ""), "ok": False, "item_count": 0, "checked_at": stamp, "url": source.get("url", ""), "error": err})
            print(f"[NEW] falha em {source.get('label')}: {exc}")

    diagnostics = {
        "schema_version": 1,
        "updated_at": stamp,
        "counts": dict(Counter(d["status"] for d in all_decisions)),
        "filtered_interest_count": filtered_interest,
        "items": all_decisions[-1000:],
    }
    save(DIAGNOSTICS, diagnostics)

    merged: dict[str, dict] = {}
    for item_id, item in found.items():
        previous = old_items.get(item_id, {})
        merged[item_id] = {**item, "first_seen": previous.get("first_seen") or stamp, "last_seen": stamp}

    new_ids = set(found) - old_seen
    new_items = [found[i] for i in new_ids]
    max_items = int(cfg.get("max_items") or 250)
    items = sorted(
        merged.values(),
        key=lambda x: (x.get("status") in {"open", "closing_soon"}, int(x.get("priority", 50)), x.get("first_seen", "")),
        reverse=True,
    )[:max_items]
    seen_ids = list(dict.fromkeys(list(old_seen) + list(found)))
    max_seen = int(cfg.get("max_seen_urls") or 5000)

    state = {
        "schema_version": 3,
        "updated_at": stamp,
        "first_run_baseline": first_run,
        "source_count": len(cfg.get("sources", [])),
        "healthy_source_count": sum(1 for h in health if h["ok"]),
        "new_count": 0 if first_run else len(new_items),
        "filtered_interest_count": filtered_interest,
        "items": items,
        "seen_ids": seen_ids[-max_seen:],
        "source_health": health,
        "failures": failures,
    }
    save(STATE, state)

    repo = os.getenv("GITHUB_REPOSITORY", "")
    token = os.getenv("GITHUB_TOKEN", "")
    if new_items and not first_run and repo and token:
        ensure_label(repo, token, "new-contest", "1f6feb", "Novo concurso/processo seletivo detectado em fonte oficial")
        for item in sorted(new_items, key=lambda x: int(x.get("priority", 50)), reverse=True)[:24]:
            try:
                create_issue(repo, token, item)
            except Exception as exc:
                print(f"[NEW] falha ao criar issue: {exc}")

    print(
        f"[NEW] fontes={state['source_count']} saudaveis={state['healthy_source_count']} "
        f"itens={len(items)} novos={state['new_count']} filtrados={filtered_interest} "
        f"rejeitados={len(all_decisions)} falhas={len(failures)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
