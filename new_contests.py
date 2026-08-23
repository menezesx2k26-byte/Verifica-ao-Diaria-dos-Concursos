#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

CONFIG = Path(os.getenv("NEW_CONTESTS_CONFIG", "config/new_contests_sources.json"))
STATE = Path(os.getenv("NEW_CONTESTS_STATE", "state/new_contests.json"))
TIMEOUT = 25


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def fold(text: str) -> str:
    import unicodedata, re, html
    text = html.unescape(text or "")
    text = unicodedata.normalize("NFKD", text)
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


def fetch(url: str) -> requests.Response:
    headers = {
        "User-Agent": "ConcursosWatch-NewContests/1.0",
        "Accept-Language": "pt-BR,pt;q=0.9",
        "Cache-Control": "no-cache",
    }
    r = requests.get(url, headers=headers, timeout=TIMEOUT, allow_redirects=True)
    r.raise_for_status()
    return r


def candidate_links(source: dict, include_terms: list[str], exclude_terms: list[str]) -> list[dict]:
    r = fetch(source["url"])
    soup = BeautifulSoup(r.text, "html.parser")
    for node in soup(["script", "style", "noscript", "svg"]):
        node.decompose()

    out: list[dict] = []
    seen: set[str] = set()
    source_host = (urlparse(r.url).hostname or "").casefold()

    for a in soup.find_all("a", href=True):
        href = urljoin(r.url, a.get("href"))
        parsed = urlparse(href)
        if parsed.scheme not in {"http", "https"}:
            continue
        title = " ".join(a.stripped_strings).strip()
        parent = " ".join(a.parent.stripped_strings).strip() if a.parent else title
        combined = fold(f"{title} {parent} {href}")
        if not any(fold(term) in combined for term in include_terms):
            continue
        if any(fold(term) in combined for term in exclude_terms):
            continue
        # Prefer links on the same official host. External official bank links may
        # still appear, but generic social/share links are ignored.
        host = (parsed.hostname or "").casefold()
        if host != source_host and not any(x in host for x in ("ibam", "vunesp", "ciee", "gov.br")):
            continue
        if href in seen:
            continue
        seen.add(href)
        out.append({
            "source_id": source["id"],
            "source": source["label"],
            "city": source.get("city", ""),
            "title": title[:240] or parent[:240] or "Novo edital/processo seletivo",
            "url": href,
        })
    return out


def ensure_label(repo: str, token: str, name: str, color: str, description: str) -> None:
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    base = f"https://api.github.com/repos/{repo}"
    r = requests.get(f"{base}/labels/{name}", headers=headers, timeout=20)
    if r.status_code == 200:
        return
    if r.status_code != 404:
        r.raise_for_status()
    c = requests.post(f"{base}/labels", headers=headers, json={
        "name": name,
        "color": color,
        "description": description,
    }, timeout=20)
    if c.status_code not in (201, 422):
        c.raise_for_status()


def create_issue(repo: str, token: str, item: dict) -> None:
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    title = f"[NOVO CONCURSO] {item['source']} — {item['title']}"[:250]
    body = (
        "Novo concurso/processo seletivo detectado em fonte oficial.\n\n"
        f"Órgão/fonte: {item['source']}\n"
        f"Localidade: {item.get('city') or 'não informada'}\n"
        f"Título detectado: {item['title']}\n\n"
        f"Fonte oficial: {item['url']}\n\n"
        "_Detecção automática. Confirme requisitos e prazo no edital antes de agir._"
    )
    payload = {"title": title, "body": body, "labels": ["new-contest"]}
    assignee = (os.getenv("ALERT_GITHUB_ASSIGNEE") or "").strip()
    if assignee:
        payload["assignees"] = [assignee]
    r = requests.post(f"https://api.github.com/repos/{repo}/issues", headers=headers, json=payload, timeout=20)
    r.raise_for_status()


def main() -> int:
    cfg = load(CONFIG, {})
    old = load(STATE, {})
    include_terms = list(cfg.get("include_terms") or [])
    exclude_terms = list(cfg.get("exclude_terms") or [])
    old_items = {x.get("url"): x for x in old.get("items", []) if x.get("url")}
    old_seen = set(old.get("seen_urls", [])) | set(old_items.keys())
    first_run = not bool(old_seen)

    found: dict[str, dict] = {}
    failures: list[dict] = []
    for source in cfg.get("sources", []):
        try:
            for item in candidate_links(source, include_terms, exclude_terms):
                found[item["url"]] = item
        except Exception as exc:
            failures.append({"source": source.get("label"), "error": str(exc)[:500]})
            print(f"[NEW] falha em {source.get('label')}: {exc}")

    stamp = now_iso()
    merged: dict[str, dict] = dict(old_items)
    for url, item in found.items():
        previous = merged.get(url, {})
        merged[url] = {
            **item,
            "first_seen": previous.get("first_seen") or stamp,
            "last_seen": stamp,
        }

    new_items = [found[url] for url in found.keys() - old_seen]
    max_items = int(cfg.get("max_items") or 60)
    items = sorted(merged.values(), key=lambda x: x.get("first_seen", ""), reverse=True)[:max_items]
    seen_urls = list(dict.fromkeys(list(old_seen) + list(found.keys())))

    state = {
        "updated_at": stamp,
        "first_run_baseline": first_run,
        "items": items,
        "seen_urls": seen_urls[-1000:],
        "failures": failures,
    }
    save(STATE, state)

    repo = os.getenv("GITHUB_REPOSITORY", "")
    token = os.getenv("GITHUB_TOKEN", "")
    if new_items and not first_run and repo and token:
        ensure_label(repo, token, "new-contest", "1f6feb", "Novo concurso/processo seletivo detectado em fonte oficial")
        for item in new_items[:12]:
            try:
                create_issue(repo, token, item)
                print(f"[NEW] issue criada: {item['title']}")
            except Exception as exc:
                print(f"[NEW] falha ao criar issue: {exc}")

    print(f"[NEW] fontes={len(cfg.get('sources', []))} itens={len(items)} novos={0 if first_run else len(new_items)} falhas={len(failures)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
