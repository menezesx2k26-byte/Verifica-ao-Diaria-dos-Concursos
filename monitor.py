#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import html
import io
import json
import os
import re
import sys
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup
from pypdf import PdfReader

CONFIG = Path(os.getenv("WATCH_CONFIG", "config/sources.json"))
STATE = Path(os.getenv("WATCH_STATE", "state/github.json"))
TIMEOUT = 25
CONTEXT_RADIUS = 500
MAX_PDF_BYTES = 20_000_000
MAX_ALERTS = 12

S = requests.Session()
S.headers.update({
    "User-Agent": "ConcursosWatch/2.0 (+personal public-notice monitor)",
    "Accept-Language": "pt-BR,pt;q=0.9,en;q=0.5",
    "Cache-Control": "no-cache",
})


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def fold(text: str) -> str:
    text = html.unescape(text or "")
    text = unicodedata.normalize("NFKD", text)
    text = "".join(c for c in text if not unicodedata.combining(c))
    text = text.casefold()
    return re.sub(r"\s+", " ", text).strip()


def digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8", "ignore")).hexdigest()


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


def fetch(url: str, *, binary: bool = False) -> requests.Response:
    last: Exception | None = None
    for attempt in range(3):
        try:
            r = S.get(url, timeout=TIMEOUT, allow_redirects=True)
            r.raise_for_status()
            if binary and len(r.content) > MAX_PDF_BYTES:
                raise RuntimeError(f"documento excede {MAX_PDF_BYTES} bytes")
            return r
        except Exception as exc:
            last = exc
            if attempt < 2:
                time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"falha ao buscar {url}: {last}")


def page_data(raw: str, base_url: str) -> tuple[str, list[dict[str, str]]]:
    soup = BeautifulSoup(raw, "html.parser")
    for node in soup(["script", "style", "noscript", "svg"]):
        node.decompose()
    links: list[dict[str, str]] = []
    for a in soup.find_all("a", href=True):
        href = urljoin(base_url, a.get("href"))
        title = " ".join(a.stripped_strings)
        parent = " ".join(a.parent.stripped_strings) if a.parent else title
        links.append({"href": href, "title": title[:500], "context": parent[:1800]})
    return soup.get_text(" ", strip=True), links


def contexts(text: str, keywords: list[str]) -> list[str]:
    t = fold(text)
    out: set[str] = set()
    for keyword in keywords:
        k = fold(keyword)
        start = 0
        while k:
            idx = t.find(k, start)
            if idx < 0:
                break
            lo = max(0, idx - CONTEXT_RADIUS)
            hi = min(len(t), idx + len(k) + CONTEXT_RADIUS)
            out.add(t[lo:hi])
            start = idx + max(1, len(k))
    return sorted(out)


def is_document(url: str) -> bool:
    x = url.casefold()
    return ".pdf" in x or "download" in x or "/arquivo" in x or "/view" in x or "edicao" in x


def pdf_text(data: bytes) -> str:
    reader = PdfReader(io.BytesIO(data))
    chunks = []
    for page in reader.pages[:100]:
        try:
            chunks.append(page.extract_text() or "")
        except Exception:
            pass
    return "\n".join(chunks)


def inspect_document(url: str, keywords: list[str]) -> tuple[bool, str]:
    r = fetch(url, binary=True)
    ctype = (r.headers.get("content-type") or "").casefold()
    if "pdf" in ctype or url.casefold().split("?")[0].endswith(".pdf"):
        text = pdf_text(r.content)
    else:
        text, _ = page_data(r.text, r.url)
    f = fold(text)
    hits = [k for k in keywords if fold(k) in f]
    if not hits:
        return False, ""
    ctx = contexts(text, hits)
    return True, (ctx[0][:1400] if ctx else fold(text)[:1400])


def event_id(label: str, body: str) -> str:
    return digest(label + "\n" + body)[:12]


def telegram(message: str) -> bool:
    token, chat = os.getenv("TELEGRAM_BOT_TOKEN"), os.getenv("TELEGRAM_CHAT_ID")
    if not token or not chat:
        return False
    r = requests.post(f"https://api.telegram.org/bot{token}/sendMessage", json={
        "chat_id": chat, "text": message[:3900], "disable_web_page_preview": True
    }, timeout=20)
    r.raise_for_status()
    return True


def ntfy(message: str) -> bool:
    topic = os.getenv("NTFY_TOPIC")
    if not topic:
        return False
    server = os.getenv("NTFY_SERVER", "https://ntfy.sh").rstrip("/")
    headers = {"Title": "Concursos Watch", "Priority": "high"}
    if os.getenv("NTFY_TOKEN"):
        headers["Authorization"] = f"Bearer {os.environ['NTFY_TOKEN']}"
    r = requests.post(f"{server}/{topic}", data=message.encode(), headers=headers, timeout=20)
    r.raise_for_status()
    return True


def resend_email(subject: str, message: str, idem: str) -> bool:
    key = os.getenv("RESEND_API_KEY")
    recipients = [x.strip() for x in os.getenv("ALERT_EMAIL_TO", "").split(",") if x.strip()]
    sender = os.getenv("ALERT_EMAIL_FROM")
    if not key or not recipients or not sender:
        return False
    r = requests.post("https://api.resend.com/emails", json={
        "from": sender,
        "to": recipients,
        "subject": subject[:200],
        "text": message,
    }, headers={
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
        "Idempotency-Key": idem,
    }, timeout=20)
    r.raise_for_status()
    return True


def github_issue(subject: str, message: str) -> bool:
    token, repo = os.getenv("GITHUB_TOKEN"), os.getenv("GITHUB_REPOSITORY")
    if not token or not repo:
        return False
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    payload = {"title": subject[:250], "body": message + "\n\n_Gerado automaticamente pelo monitor do GitHub Actions._", "labels": ["watch-alert"]}
    r = requests.post(f"https://api.github.com/repos/{repo}/issues", json=payload, headers=headers, timeout=20)
    if r.status_code == 422:
        payload.pop("labels", None)
        r = requests.post(f"https://api.github.com/repos/{repo}/issues", json=payload, headers=headers, timeout=20)
    r.raise_for_status()
    return True


def notify(subject: str, message: str, eid: str) -> None:
    failures = []
    for name, fn in (
        ("telegram", lambda: telegram(message)),
        ("ntfy", lambda: ntfy(message)),
        ("resend", lambda: resend_email(subject, message, eid)),
        ("github_issue", lambda: github_issue(subject, message)),
    ):
        try:
            fn()
        except Exception as exc:
            failures.append(f"{name}: {exc}")
    if failures:
        print("[WARN] canais com falha: " + " | ".join(failures), file=sys.stderr)


def cloudflare_heartbeat() -> None:
    url = os.getenv("CLOUDFLARE_HEARTBEAT_URL")
    if not url:
        return
    headers = {"content-type": "application/json"}
    if os.getenv("WATCHDOG_TOKEN"):
        headers["authorization"] = f"Bearer {os.environ['WATCHDOG_TOKEN']}"
    r = requests.post(url, headers=headers, json={"at": now_iso(), "source": "github-actions"}, timeout=15)
    r.raise_for_status()


def check_cloudflare_health() -> str | None:
    url = os.getenv("CLOUDFLARE_HEALTH_URL")
    if not url:
        return None
    r = requests.get(url, timeout=15)
    r.raise_for_status()
    data = r.json()
    stamp = data.get("last_cloudflare_run")
    if not stamp:
        return "Cloudflare ainda não registrou uma execução."
    ts = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    age = (datetime.now(timezone.utc) - ts).total_seconds() / 60
    if age > 35:
        return f"Cloudflare está sem heartbeat há {age:.0f} minutos."
    return None


def check_source(source: dict[str, Any], old: dict[str, Any]) -> tuple[dict[str, Any], list[tuple[str, str]]]:
    r = fetch(source["url"])
    text, links = page_data(r.text, r.url)
    label = source["label"]
    keywords = source["keywords"]
    events: list[tuple[str, str]] = []
    new = dict(old)
    new.update({"failures": 0, "resolved_url": r.url, "last_status": r.status_code})
    if old.get("failures", 0):
        events.append((f"RECUPERADO — {label}", f"✅ A fonte voltou a responder.\n{source['url']}"))

    if source["mode"] == "page_context":
        ctxs = contexts(text, keywords)
        sigs = sorted(digest(x) for x in ctxs)
        if "context_sigs" in old:
            previous = set(old["context_sigs"])
            added = [x for x in ctxs if digest(x) not in previous]
            if added:
                sample = "\n\n".join(f"• {x[:1000]}" for x in added[:4])
                events.append((f"NOVIDADE — {label}", f"🚨 Conteúdo relevante novo detectado.\n\n{sample}\n\nFonte: {source['url']}"))
        new["context_sigs"] = sigs
        new["context_count"] = len(sigs)
    else:
        docs: dict[str, str] = {}
        for item in links:
            if is_document(item["href"]):
                docs[item["href"]] = item["title"] or item["context"][:180]
        urls = sorted(docs)
        if "document_urls" in old:
            previous = set(old["document_urls"])
            for url in [u for u in urls if u not in previous][:10]:
                try:
                    ok, snippet = inspect_document(url, keywords)
                except Exception as exc:
                    print(f"[WARN] documento não inspecionado {url}: {exc}", file=sys.stderr)
                    continue
                if ok:
                    title = docs.get(url, "Documento novo")
                    events.append((f"DOCUMENTO — {label}", f"🚨 Documento novo com termos relevantes.\n{title}\n\n{snippet}\n\nDocumento: {url}\nÍndice: {source['url']}"))
        new["document_urls"] = urls[-400:]
        new["document_count"] = len(urls)
    return new, events


def main() -> int:
    cfg = load(CONFIG, {})
    state = load(STATE, {})
    threshold = int(cfg.get("failure_threshold", 3))
    events: list[tuple[str, str]] = []
    failed = 0

    for source in cfg.get("sources", []):
        sid = source["id"]
        old = state.get(sid, {})
        print(f"[CHECK] {source['label']}")
        try:
            new, evs = check_source(source, old)
            state[sid] = new
            events.extend(evs)
        except Exception as exc:
            failed += 1
            prev = int(old.get("failures", 0))
            count = min(prev + 1, threshold)
            state[sid] = {**old, "failures": count, "last_error": str(exc)[:1000]}
            print(f"[ERROR] {source['label']}: {exc}", file=sys.stderr)
            if count == threshold and prev < threshold:
                events.append((f"FONTE INDISPONÍVEL — {source['label']}", f"⚠️ A fonte falhou {threshold} verificações seguidas.\nErro: {exc}\n{source['url']}"))

    try:
        cloudflare_heartbeat()
    except Exception as exc:
        print(f"[WARN] heartbeat Cloudflare falhou: {exc}", file=sys.stderr)

    try:
        problem = check_cloudflare_health()
        old_problem = bool(state.get("_cloudflare_watchdog_problem"))
        if problem and not old_problem:
            events.append(("WATCHDOG — Cloudflare", f"⚠️ {problem}"))
            state["_cloudflare_watchdog_problem"] = True
        elif not problem and old_problem:
            events.append(("RECUPERADO — Cloudflare", "✅ O monitor Cloudflare voltou a ficar saudável."))
            state["_cloudflare_watchdog_problem"] = False
    except Exception as exc:
        print(f"[WARN] health Cloudflare não pôde ser consultado: {exc}", file=sys.stderr)

    save(STATE, state)

    for subject, body in events[:MAX_ALERTS]:
        eid = event_id(subject, body)
        full_subject = f"[CONCURSOS][{eid}] {subject}"
        print("\n" + full_subject + "\n" + body)
        notify(full_subject, body, eid)

    total = len(cfg.get("sources", []))
    return 2 if total and failed == total else 0


if __name__ == "__main__":
    raise SystemExit(main())
