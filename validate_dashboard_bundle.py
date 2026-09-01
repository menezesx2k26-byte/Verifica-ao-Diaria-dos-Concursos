#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse

FORBIDDEN_TAGS = {
    "script",
    "iframe",
    "object",
    "embed",
    "form",
    "input",
    "textarea",
    "select",
    "button",
    "base",
}
REMOTE_CSS = re.compile(r"url\s*\(\s*['\"]?\s*(?:https?:|//)", re.IGNORECASE)


class _DashboardHtmlParser(HTMLParser):
    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self._validate_tag(tag, attrs)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self._validate_tag(tag, attrs)

    def _validate_tag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.casefold()
        if tag in FORBIDDEN_TAGS:
            raise ValueError(f"forbidden HTML tag: {tag}")
        attr_map = {name.casefold(): (value or "") for name, value in attrs}
        if any(name.startswith("on") for name in attr_map):
            raise ValueError("event handler attributes are forbidden")
        if "style" in attr_map:
            raise ValueError("inline style is forbidden")
        if tag == "meta" and attr_map.get("http-equiv", "").casefold() == "refresh":
            raise ValueError("meta refresh is forbidden")

        for name in ("src", "href"):
            raw = attr_map.get(name)
            if raw is None:
                continue
            value = raw.strip()
            lower = value.casefold()
            if lower.startswith("javascript:"):
                raise ValueError("javascript URL is forbidden")
            if name == "src":
                if lower.startswith(("http://", "https://", "//")):
                    raise ValueError("remote resource URL is forbidden")
                if value and not (
                    value.startswith("/")
                    or value.startswith("data:image/")
                    or value.startswith("#")
                ):
                    raise ValueError("unsupported src URL")
            else:
                if tag == "link":
                    if value.split("?", 1)[0] != "/assets/dashboard.css":
                        raise ValueError("only local dashboard CSS may be linked")
                    rel = attr_map.get("rel", "").casefold()
                    if "stylesheet" not in rel:
                        raise ValueError("dashboard link must be stylesheet")
                    continue
                if lower.startswith("concursoswatch://") or value.startswith("#"):
                    continue
                if lower.startswith("https://"):
                    parsed = urlparse(value)
                    if not parsed.hostname or parsed.username or parsed.password:
                        raise ValueError("invalid HTTPS navigation URL")
                    continue
                if value.startswith("/"):
                    continue
                raise ValueError("unsupported href URL")


def validate_html(text: str) -> None:
    parser = _DashboardHtmlParser(convert_charrefs=True)
    parser.feed(text)
    parser.close()


def validate_css(text: str) -> None:
    lower = text.casefold()
    if "@import" in lower:
        raise ValueError("CSS @import is forbidden")
    if "javascript:" in lower or "expression(" in lower:
        raise ValueError("executable CSS is forbidden")
    if REMOTE_CSS.search(text):
        raise ValueError("remote CSS resources are forbidden")


def validate_files(html_path: Path, css_path: Path) -> None:
    validate_html(html_path.read_text(encoding="utf-8"))
    validate_css(css_path.read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if len(args) != 2:
        raise SystemExit("usage: validate_dashboard_bundle.py HTML CSS")
    validate_files(Path(args[0]), Path(args[1]))
    print("dashboard bundle security OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
