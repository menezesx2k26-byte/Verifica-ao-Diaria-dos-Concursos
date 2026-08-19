from __future__ import annotations

import os
import smtplib
import ssl
from email.message import EmailMessage
from typing import Callable


def _recipients() -> list[str]:
    raw = os.getenv("GMAIL_ALERT_TO") or os.getenv("ALERT_EMAIL_TO") or ""
    return [x.strip() for x in raw.split(",") if x.strip()]


def send_gmail(subject: str, message: str) -> bool:
    """Send an independent alert through Gmail SMTP when configured.

    Use a Google App Password, never the account's normal password.  Nothing is
    sent when the required secrets are absent, so this remains fully optional.
    """
    username = (os.getenv("GMAIL_SMTP_USER") or "").strip()
    app_password = (os.getenv("GMAIL_SMTP_APP_PASSWORD") or "").replace(" ", "").strip()
    recipients = _recipients()
    if not username or not app_password or not recipients:
        return False

    sender = (os.getenv("GMAIL_SMTP_FROM") or username).strip()
    host = (os.getenv("GMAIL_SMTP_HOST") or "smtp.gmail.com").strip()
    port = int(os.getenv("GMAIL_SMTP_PORT") or "465")

    email = EmailMessage()
    email["From"] = sender
    email["To"] = ", ".join(recipients)
    email["Subject"] = subject[:200]
    email.set_content(message)

    context = ssl.create_default_context()
    with smtplib.SMTP_SSL(host, port, timeout=25, context=context) as smtp:
        smtp.login(username, app_password)
        smtp.send_message(email)
    return True


def augment_notify(base_notify: Callable):
    """Wrap monitor.notify without coupling SMTP to the core detector."""

    def wrapped(subject: str, message: str, eid: str, *, create_issue: bool = True):
        delivered = list(base_notify(subject, message, eid, create_issue=create_issue))
        try:
            if send_gmail(subject, message):
                delivered.append("gmail_smtp")
                print("[NOTIFY] Gmail SMTP entregue")
        except Exception as exc:
            # A falha de um canal nunca deve apagar os demais alertas.
            print(f"[WARN] Gmail SMTP falhou: {exc}")
        return delivered

    return wrapped
