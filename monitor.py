#!/usr/bin/env python3
from __future__ import annotations

import argparse
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
from typing import Any, Callable
from urllib.parse import urljoin
from zoneinfo import ZoneInfo

import requests
from bs4 import BeautifulSoup
from pypdf import PdfReader

CONFIG = Path(os.getenv("WATCH_CONFIG", "config/sources.json"))
STATE = Path(os.getenv("WATCH_STATE", "state/github.json"))
RUNTIME_CONFIG = Path(os.getenv("WATCH_RUNTIME_CONFIG", "config/runtime.json"))
TIMEOUT = 25
DEFAULT_CONTEXT_RADIUS = 650
MAX_PDF_BYTES = 20_000_000
MAX_HTML_BYTES = 8_000_000
MAX_ALERTS = 16

S = requests.Session()
S.headers.update({
    "User-Agent": "ConcursosWatch/3.0 (+personal public-notice monitor)",
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


def runtime_cloudflare_base() -> str | None:
    data = load(RUNTIME_CONFIG, {})
    value = str(data.get("cloudflare_url") or "").strip().rstrip("/")
    return value or None


def priority_terms() -> list[str]:
    raw = os.getenv("WATCH_PRIORITY_TERMS", "")
    parts = re.split(r"[,;\n]+", raw)
    return [p.strip() for p in parts if p.strip()]


def fetch(url: str, *, binary: bool = False) -> requests.Response:
    last: Exception | None = None
    for attempt in range(3):
        try:
            r = S.get(url, timeout=TIMEOUT, allow_redirects=True)
            r.raise_for_status()
            size = len(r.content)
            if binary and size > MAX_PDF_BYTES:
                raise RuntimeError(f"documento excede {MAX_PDF_BYTES} bytes")
            if not binary and size > MAX_HTML_BYTES:
                raise RuntimeError(f"HTML excede {MAX_HTML_BYTES} bytes")
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
        links.append({"href": href, "title": title[:500], "context": parent[:2200]})
    return soup.get_text(" ", strip=True), links


def contexts(text: str, keywords: list[str], radius: int = DEFAULT_CONTEXT_RADIUS) -> list[str]:
    t = fold(text)
    out: set[str] = set()
    for keyword in keywords:
        k = fold(keyword)
        start = 0
        while k:
            idx = t.find(k, start)
            if idx < 0:
                break
            lo = max(0, idx - radius)
            hi = min(len(t), idx + len(k) + radius)
            out.add(t[lo:hi])
            start = idx + max(1, len(k))
    return sorted(out)


def groups_match(text: str, groups: list[list[str]] | None) -> bool:
    if not groups:
        return True
    t = fold(text)
    return all(any(fold(term) in t for term in group if term) for group in groups)


def any_match(text: str, terms: list[str] | None) -> bool:
    if not terms:
        return True
    t = fold(text)
    return any(fold(term) in t for term in terms if term)


def is_document(url: str) -> bool:
    x = url.casefold()
    return (
        ".pdf" in x
        or "download" in x
        or "/arquivo" in x
        or "/view" in x
        or "edicao" in x
        or "publicacao" in x
    )


def pdf_text(data: bytes) -> str:
    reader = PdfReader(io.BytesIO(data))
    chunks = []
    for page in reader.pages[:120]:
        try:
            chunks.append(page.extract_text() or "")
        except Exception:
            pass
    return "\n".join(chunks)


def source_trigger_terms(source: dict[str, Any]) -> list[str]:
    return list(source.get("trigger_terms") or source.get("keywords") or [])


def relevant_contexts(text: str, source: dict[str, Any]) -> list[str]:
    triggers = source_trigger_terms(source)
    terms = list(dict.fromkeys(triggers + priority_terms()))
    radius = int(source.get("context_radius", DEFAULT_CONTEXT_RADIUS))
    candidates = contexts(text, terms, radius=radius)
    out: list[str] = []
    pterms = priority_terms()
    for ctx in candidates:
        if pterms and any_match(ctx, pterms):
            out.append(ctx)
            continue
        if groups_match(ctx, source.get("require_groups")) and any_match(ctx, triggers):
            out.append(ctx)
    return sorted(set(out))


def document_result(text: str, source: dict[str, Any]) -> tuple[bool, bool, str]:
    f = fold(text)
    pterms = priority_terms()
    priority_hits = [p for p in pterms if fold(p) in f]
    if priority_hits:
        ctx = contexts(text, priority_hits, radius=900)
        return True, True, (ctx[0][:1600] if ctx else f[:1600])

    groups = source.get("document_require_groups") or source.get("require_groups")
    triggers = source_trigger_terms(source)
    matched = groups_match(text, groups) and any_match(text, triggers)
    if not matched:
        return False, False, ""

    hits = [t for t in triggers if fold(t) in f]
    ctx = contexts(text, hits or triggers[:3], radius=900)
    return True, False, (ctx[0][:1600] if ctx else f[:1600])


def inspect_document(url: str, source: dict[str, Any]) -> tuple[bool, bool, bool, str]:
    r = fetch(url, binary=True)
    ctype = (r.headers.get("content-type") or "").casefold()
    unreadable = False
    if "pdf" in ctype or url.casefold().split("?")[0].endswith(".pdf"):
        try:
            text = pdf_text(r.content)
        except Exception:
            text = ""
        if len(fold(text)) < 80:
            unreadable = True
    else:
        try:
            text, _ = page_data(r.text, r.url)
        except Exception:
            text = r.text

    matched, priority, snippet = document_result(text, source)
    return matched, priority, unreadable, snippet


def event_id(subject: str, body: str) -> str:
    return digest(subject + "\n" + body)[:12]


def telegram(subject: str, message: str) -> bool:
    token, chat = os.getenv("TELEGRAM_BOT_TOKEN"), os.getenv("TELEGRAM_CHAT_ID")
    if not token or not chat:
        return False
    text = f"{subject}\n\n{message}"
    r = requests.post(f"https://api.telegram.org/bot{token}/sendMessage", json={
        "chat_id": chat, "text": text[:3900], "disable_web_page_preview": True
    }, timeout=20)
    r.raise_for_status()
    return True


def ntfy(subject: str, message: str) -> bool:
    topic = os.getenv("NTFY_TOPIC")
    if not topic:
        return False
    server = os.getenv("NTFY_SERVER", "https://ntfy.sh").rstrip("/")
    headers = {"Title": subject[:120], "Priority": "high"}
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


def github_issue(subject: str, message: str, eid: str) -> bool:
    token, repo = os.getenv("GITHUB_TOKEN"), os.getenv("GITHUB_REPOSITORY")
    if not token or not repo:
        return False
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    payload: dict[str, Any] = {
        "title": subject[:250],
        "body": f"{message}\n\nEvent ID: `{eid}`\n\n_Gerado automaticamente pelo monitor do GitHub Actions._",
        "labels": ["watch-alert"],
    }
    assignee = (os.getenv("ALERT_GITHUB_ASSIGNEE") or "").strip()
    if assignee:
        payload["assignees"] = [assignee]
    r = requests.post(f"https://api.github.com/repos/{repo}/issues", json=payload, headers=headers, timeout=20)
    if r.status_code == 422:
        payload.pop("labels", None)
        r = requests.post(f"https://api.github.com/repos/{repo}/issues", json=payload, headers=headers, timeout=20)
    r.raise_for_status()
    return True


def notify(subject: str, message: str, eid: str, *, create_issue: bool = True) -> list[str]:
    failures: list[str] = []
    delivered: list[str] = []
    channels: list[tuple[str, Callable[[], bool]]] = [
        ("telegram", lambda: telegram(subject, message)),
        ("ntfy", lambda: ntfy(subject, message)),
        ("resend", lambda: resend_email(subject, message, eid)),
    ]
    if create_issue:
        channels.append(("github_issue", lambda: github_issue(subject, message, eid)))

    for name, fn in channels:
        try:
            if fn():
                delivered.append(name)
        except Exception as exc:
            failures.append(f"{name}: {exc}")
    print("[NOTIFY] entregues=" + (",".join(delivered) if delivered else "nenhum canal externo configurado"))
    if failures:
        print("[WARN] canais com falha: " + " | ".join(failures), file=sys.stderr)
    return delivered


def cloudflare_heartbeat() -> None:
    base = runtime_cloudflare_base()
    url = os.getenv("CLOUDFLARE_HEARTBEAT_URL") or (f"{base}/heartbeat/github" if base else None)
    if not url:
        return
    token = os.getenv("WATCHDOG_TOKEN")
    if not token:
        raise RuntimeError("WATCHDOG_TOKEN ausente; heartbeat Cloudflare não pode ser autenticado")
    r = requests.post(
        url,
        headers={"content-type": "application/json", "authorization": f"Bearer {token}"},
        json={"at": now_iso(), "source": "github-actions"},
        timeout=15,
    )
    r.raise_for_status()


def check_cloudflare_health(stale_minutes: int) -> str | None:
    base = runtime_cloudflare_base()
    url = os.getenv("CLOUDFLARE_HEALTH_URL") or (f"{base}/health" if base else None)
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
    if age > stale_minutes:
        return f"Cloudflare está sem heartbeat há {age:.0f} minutos."
    return None


def link_candidate_matches(item: dict[str, str], source: dict[str, Any]) -> bool:
    combined = f"{item.get('title', '')} {item.get('context', '')} {item.get('href', '')}"
    if priority_terms() and any_match(combined, priority_terms()):
        return True
    groups = source.get("document_link_require_groups") or source.get("document_require_groups")
    return groups_match(combined, groups) and any_match(combined, source_trigger_terms(source))


def check_source(source: dict[str, Any], old: dict[str, Any]) -> tuple[dict[str, Any], list[tuple[str, str]]]:
    r = fetch(source["url"])
    text, links = page_data(r.text, r.url)
    label = source["label"]
    mode = source.get("mode", "page_context")
    events: list[tuple[str, str]] = []
    new = dict(old)
    new.update({"failures": 0, "resolved_url": r.url, "last_status": r.status_code})
    if old.get("failures", 0):
        events.append((f"RECUPERADO — {label}", f"✅ A fonte voltou a responder.\n{source['url']}"))

    if mode in {"page_context", "hybrid"}:
        ctxs = relevant_contexts(text, source)
        sigs = sorted(digest(x) for x in ctxs)
        if "context_sigs" in old:
            previous = set(old["context_sigs"])
            added = [x for x in ctxs if digest(x) not in previous]
            if added:
                sample = "\n\n".join(f"• {x[:1100]}" for x in added[:4])
                priority = bool(priority_terms()) and any(any_match(x, priority_terms()) for x in added)
                prefix = "PRIORIDADE — " if priority else "NOVIDADE — "
                events.append((
                    f"{prefix}{label}",
                    f"🚨 Conteúdo relevante novo detectado.\n\n{sample}\n\nFonte: {source['url']}",
                ))
        new["context_sigs"] = sigs
        new["context_count"] = len(sigs)

    if mode in {"documents", "hybrid"}:
        docs: dict[str, dict[str, str]] = {}
        for item in links:
            if is_document(item["href"]):
                docs[item["href"]] = item
        urls = sorted(docs)
        if "document_urls" in old:
            previous = set(old["document_urls"])
            for url in [u for u in urls if u not in previous][:12]:
                item = docs[url]
                try:
                    matched, priority, unreadable, snippet = inspect_document(url, source)
                except Exception as exc:
                    print(f"[WARN] documento não inspecionado {url}: {exc}", file=sys.stderr)
                    if link_candidate_matches(item, source):
                        events.append((
                            f"CANDIDATO NÃO LIDO — {label}",
                            f"⚠️ Surgiu um documento/link fortemente relacionado, mas a leitura falhou.\n"
                            f"Título: {item.get('title') or '(sem título)'}\nDocumento: {url}\nÍndice: {source['url']}",
                        ))
                    continue

                if matched:
                    title = item.get("title") or "Documento novo"
                    prefix = "PRIORIDADE — " if priority else "DOCUMENTO — "
                    events.append((
                        f"{prefix}{label}",
                        f"🚨 Documento novo com correspondência relevante.\n{title}\n\n{snippet}\n\n"
                        f"Documento: {url}\nÍndice: {source['url']}",
                    ))
                elif unreadable and link_candidate_matches(item, source):
                    events.append((
                        f"PDF NÃO EXTRAÍVEL — {label}",
                        f"⚠️ Documento novo parece relacionado, mas o PDF não forneceu texto extraível.\n"
                        f"Título: {item.get('title') or '(sem título)'}\nDocumento: {url}\nÍndice: {source['url']}",
                    ))
        new["document_urls"] = urls[-500:]
        new["document_count"] = len(urls)

    return new, events


def daily_health_due(cfg: dict[str, Any], state: dict[str, Any]) -> tuple[bool, str]:
    health = cfg.get("daily_health") or {}
    if not health.get("enabled", True):
        return False, ""
    tz_name = health.get("timezone", "America/Sao_Paulo")
    hour = int(health.get("hour", 9))
    local = datetime.now(ZoneInfo(tz_name))
    day = local.date().isoformat()
    if local.hour < hour:
        return False, day
    return state.get("_last_daily_health_date") != day, day


def health_body(cfg: dict[str, Any], state: dict[str, Any], failed_now: int) -> str:
    rows = []
    for source in cfg.get("sources", []):
        s = state.get(source["id"], {})
        status = "✅" if int(s.get("failures", 0)) == 0 else f"⚠️ falhas={s.get('failures')}"
        rows.append(f"{status} {source['label']}")
    cf = runtime_cloudflare_base()
    cf_text = f"configurado em {cf}" if cf else "ainda sem URL registrada"
    return (
        "💓 Heartbeat diário do Concursos Watch.\n\n"
        f"GitHub: execução concluída; falhas nesta rodada: {failed_now}.\n"
        f"Cloudflare: {cf_text}.\n\n"
        + "\n".join(rows)
    )


def self_test() -> int:
    assert fold("São Vicente") == "sao vicente"
    assert groups_match("concurso 004/2024 agente comunitario de saude convocacao", [
        ["004/2024"], ["agente comunitario de saude"], ["convoca", "nomea"]
    ])
    assert not groups_match("concurso 004/2024 professor", [["agente comunitario de saude"]])
    assert contexts("abc convocação xyz", ["convocação"], 10)
    assert len(event_id("a", "b")) == 12
    print("[SELF-TEST] OK")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--test-alert", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()

    cfg = load(CONFIG, {})
    state = load(STATE, {})
    threshold = int(cfg.get("failure_threshold", 3))
    stale_minutes = int(cfg.get("cloudflare_stale_minutes", 40))
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
                events.append((
                    f"FONTE INDISPONÍVEL — {source['label']}",
                    f"⚠️ A fonte falhou {threshold} verificações seguidas.\nErro: {exc}\n{source['url']}",
                ))

    try:
        cloudflare_heartbeat()
    except Exception as exc:
        print(f"[WARN] heartbeat Cloudflare falhou: {exc}", file=sys.stderr)

    try:
        problem = check_cloudflare_health(stale_minutes)
        old_problem = bool(state.get("_cloudflare_watchdog_problem"))
        if problem and not old_problem:
            events.append(("WATCHDOG — Cloudflare", f"⚠️ {problem}"))
            state["_cloudflare_watchdog_problem"] = True
        elif not problem and old_problem:
            events.append(("RECUPERADO — Cloudflare", "✅ O monitor Cloudflare voltou a ficar saudável."))
            state["_cloudflare_watchdog_problem"] = False
    except Exception as exc:
        print(f"[WARN] health Cloudflare não pôde ser consultado: {exc}", file=sys.stderr)

    test_alert = args.test_alert or fold(os.getenv("WATCH_TEST_ALERT", "")) in {"1", "true", "yes", "sim"}
    if test_alert:
        events.append((
            "TESTE OPERACIONAL",
            "🧪 Teste manual do Concursos Watch. Se esta mensagem chegou por mais de um canal, a redundância de entrega está funcionando.",
        ))

    # Persiste a detecção antes das notificações para evitar alertas duplicados em caso
    # de falha isolada de Telegram/e-mail após a fonte já ter sido processada.
    save(STATE, state)

    for subject, body in events[:MAX_ALERTS]:
        eid = event_id(subject, body)
        full_subject = f"[CONCURSOS][{eid}] {subject}"
        print("\n" + full_subject + "\n" + body)
        notify(full_subject, body, eid)

    due, day = daily_health_due(cfg, state)
    if due:
        subject = f"[CONCURSOS][HEALTH-{day}] SAÚDE DIÁRIA"
        body = health_body(cfg, state, failed)
        delivered = notify(subject, body, f"health-{day}", create_issue=False)
        if delivered:
            state["_last_daily_health_date"] = day
            save(STATE, state)

    total = len(cfg.get("sources", []))
    return 2 if total and failed == total else 0


if __name__ == "__main__":
    raise SystemExit(main())
