#!/usr/bin/env python3
"""Runtime wrapper for resilient transports and independent alert delivery.

Direct HTTP is always attempted first. A public Reader proxy is used only for
allow-listed official hosts when the origin blocks the GitHub runner. The proxy
must prove that it actually returned the expected official page before its
response is accepted; a generic WAF/error page can never clear a source failure.

The wrapper also augments the core alert fan-out with optional Gmail SMTP. That
keeps a personal mailbox path independent of Resend and Cloudflare Email.
"""
from __future__ import annotations

import html
import re
import sys
import time
from urllib.parse import urlparse

import requests

import monitor
from smtp_alert import augment_notify

READER_FALLBACK_HOSTS = {
    "ibamsp-concursos.org.br",
    "www.ibamsp-concursos.org.br",
}
READER_BASE = "https://r.jina.ai/"

# Every inner list is OR; groups are AND. The proxy response is accepted only
# when all groups are represented in the returned public content.
FALLBACK_REQUIRED_GROUPS: dict[str, list[list[str]]] = {
    "/informacoes/134/": [
        ["são vicente", "sao vicente"],
        ["02/2026", "02-2026"],
        ["concurso público", "concurso publico"],
    ],
}

FALLBACK_USED: set[str] = set()
FALLBACK_META: dict[str, dict[str, object]] = {}

_direct_fetch = monitor.fetch
_direct_inspect_document = monitor.inspect_document
_direct_check_source = monitor.check_source
_direct_notify = monitor.notify


def _allowed(url: str) -> bool:
    return (urlparse(url).hostname or "").casefold() in READER_FALLBACK_HOSTS


def _required_groups(url: str) -> list[list[str]]:
    path = urlparse(url).path
    for prefix, groups in FALLBACK_REQUIRED_GROUPS.items():
        if path.startswith(prefix):
            return groups
    return []


def _validate_reader_content(url: str, text: str) -> dict[str, object]:
    normalized = monitor.fold(text)
    groups = _required_groups(url)
    hits: list[list[str]] = []
    for group in groups:
        group_hits = [term for term in group if monitor.fold(term) in normalized]
        hits.append(group_hits)
    if groups and not all(hits):
        raise RuntimeError(
            "Reader respondeu, mas não comprovou a identidade da página oficial "
            f"(grupos encontrados={hits})"
        )
    if len(normalized) < 300:
        raise RuntimeError(f"Reader devolveu conteúdo insuficiente ({len(normalized)} caracteres)")
    return {
        "chars": len(normalized),
        "identity_groups": [len(x) for x in hits],
    }


def _reader_markdown(url: str) -> str:
    reader_url = READER_BASE + url
    last: Exception | None = None
    for attempt in range(3):
        try:
            r = requests.get(
                reader_url,
                headers={
                    "Accept": "text/plain",
                    "User-Agent": "ConcursosWatch/6.0 reader-fallback",
                    "X-No-Cache": "true",
                    "X-Cache-Tolerance": "0",
                    "X-Target-Selector": "body",
                    "X-Locale": "pt-BR",
                    "X-User-Agent": (
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                        "(KHTML, like Gecko) Chrome/140.0 Safari/537.36"
                    ),
                    "X-Referer": "https://www.ibamsp-concursos.org.br/",
                    "X-Timeout": "30",
                },
                timeout=50,
            )
            r.raise_for_status()
            text = r.text
            meta = _validate_reader_content(url, text)
            FALLBACK_USED.add(url)
            FALLBACK_META[url] = {
                **meta,
                "status": r.status_code,
                "attempt": attempt + 1,
            }
            print(
                f"[FALLBACK] Reader validado para {url} "
                f"chars={meta['chars']} attempt={attempt + 1}"
            )
            return text
        except Exception as exc:
            last = exc
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"fallback Reader falhou/foi rejeitado para {url}: {last}")


def _markdown_as_html(markdown: str) -> str:
    # Preserve complete markdown as text and expose absolute markdown links as
    # anchors so the existing parser can discover official PDF URLs.
    anchors: list[str] = []
    for title, url in re.findall(r"\[([^\]]{1,500})\]\((https?://[^)\s]+)\)", markdown):
        anchors.append(
            f'<a href="{html.escape(url, quote=True)}">{html.escape(title)}</a>'
        )
    body = html.escape(markdown)
    return "<main><pre>" + body + "</pre>" + "\n".join(anchors) + "</main>"


def fetch_with_fallback(url: str, *, binary: bool = False):
    try:
        return _direct_fetch(url, binary=binary)
    except Exception:
        if not _allowed(url) or binary:
            raise

    markdown = _reader_markdown(url)
    r = requests.Response()
    r.status_code = 200
    r.url = url
    r.encoding = "utf-8"
    r.headers["content-type"] = "text/html; charset=utf-8"
    r.headers["x-concursos-watch-transport"] = "jina-reader"
    r._content = _markdown_as_html(markdown).encode("utf-8")
    return r


def inspect_document_with_fallback(url: str, source):
    result = None
    try:
        result = _direct_inspect_document(url, source)
        matched, priority, unreadable, snippet = result
        if not unreadable:
            return result
    except Exception:
        if not _allowed(url):
            raise

    if not _allowed(url):
        assert result is not None
        return result

    markdown = _reader_markdown(url)
    matched, priority, snippet = monitor.document_result(markdown, source)
    return matched, priority, False, snippet


def check_source_with_transport(source, old):
    before = set(FALLBACK_USED)
    new, events = _direct_check_source(source, old)
    used_now = FALLBACK_USED - before
    source_url = source.get("url")
    if source_url in FALLBACK_USED or used_now:
        new["transport"] = "reader-fallback"
        if source_url in FALLBACK_META:
            new["transport_meta"] = FALLBACK_META[source_url]
    else:
        new["transport"] = "direct"
        new.pop("transport_meta", None)
    return new, events


monitor.fetch = fetch_with_fallback
monitor.inspect_document = inspect_document_with_fallback
monitor.check_source = check_source_with_transport
monitor.notify = augment_notify(_direct_notify)

if __name__ == "__main__":
    raise SystemExit(monitor.main(sys.argv[1:]))
