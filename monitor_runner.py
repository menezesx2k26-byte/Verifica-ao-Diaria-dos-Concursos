#!/usr/bin/env python3
"""Runtime wrapper for transports that should not contaminate the core parser.

Direct HTTP is always attempted first.  A public Reader proxy is used only for
allow-listed official hosts when the origin blocks the GitHub runner.  Alerts
still identify the official URL as the source; state records which transport
was necessary.
"""
from __future__ import annotations

import html
import re
import sys
from urllib.parse import urlparse

import requests

import monitor

READER_FALLBACK_HOSTS = {
    "ibamsp-concursos.org.br",
    "www.ibamsp-concursos.org.br",
}
READER_BASE = "https://r.jina.ai/"
FALLBACK_USED: set[str] = set()

_direct_fetch = monitor.fetch
_direct_inspect_document = monitor.inspect_document
_direct_check_source = monitor.check_source


def _allowed(url: str) -> bool:
    return (urlparse(url).hostname or "").casefold() in READER_FALLBACK_HOSTS


def _reader_markdown(url: str) -> str:
    reader_url = READER_BASE + url
    last: Exception | None = None
    for attempt in range(2):
        try:
            r = requests.get(
                reader_url,
                headers={
                    "Accept": "text/plain",
                    "User-Agent": "ConcursosWatch/5.0 reader-fallback",
                },
                timeout=45,
            )
            r.raise_for_status()
            text = r.text
            if len(text.strip()) < 100:
                raise RuntimeError("Reader devolveu conteúdo vazio/curto")
            FALLBACK_USED.add(url)
            print(f"[FALLBACK] Reader transport usado para {url}")
            return text
        except Exception as exc:
            last = exc
            if attempt == 0:
                import time
                time.sleep(2)
    raise RuntimeError(f"fallback Reader falhou para {url}: {last}")


def _markdown_as_html(markdown: str) -> str:
    # Preserve the complete markdown as text and expose absolute markdown links
    # as anchors so the existing parser can discover official PDF URLs.
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
    try:
        result = _direct_inspect_document(url, source)
        matched, priority, unreadable, snippet = result
        if not unreadable:
            return result
    except Exception:
        if not _allowed(url):
            raise

    if not _allowed(url):
        return matched, priority, unreadable, snippet

    markdown = _reader_markdown(url)
    matched, priority, snippet = monitor.document_result(markdown, source)
    return matched, priority, False, snippet


def check_source_with_transport(source, old):
    before = set(FALLBACK_USED)
    new, events = _direct_check_source(source, old)
    used_now = FALLBACK_USED - before
    if source.get("url") in FALLBACK_USED or used_now:
        new["transport"] = "reader-fallback"
    else:
        new["transport"] = "direct"
    return new, events


monitor.fetch = fetch_with_fallback
monitor.inspect_document = inspect_document_with_fallback
monitor.check_source = check_source_with_transport

if __name__ == "__main__":
    raise SystemExit(monitor.main(sys.argv[1:]))
