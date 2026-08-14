"""Transactional email delivery for account verification and recovery."""

from __future__ import annotations

import smtplib
import ssl
import json
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from email.message import EmailMessage

import config


def configured() -> bool:
    if config.EMAIL_PROVIDER == "resend":
        return bool(config.RESEND_API_KEY and config.RESEND_FROM)
    return bool(config.SMTP_HOST and config.SMTP_FROM)


def send_code(recipient: str, subject: str, code: str, purpose: str) -> None:
    if not configured():
        raise RuntimeError("email delivery is not configured")
    text = (
        f"SecureMsg {purpose} 코드\n\n"
        f"인증 코드: {code}\n\n"
        f"이 코드는 {config.EMAIL_CODE_TTL_SECONDS // 60}분 동안 유효합니다. "
        "본인이 요청하지 않았다면 이 메일을 무시하세요.\n"
    )
    if config.EMAIL_PROVIDER == "resend":
        payload = json.dumps({
            "from": config.RESEND_FROM,
            "to": [recipient],
            "subject": subject,
            "text": text,
        }).encode("utf-8")
        request = Request(
            config.RESEND_API_URL,
            data=payload,
            method="POST",
            headers={
                "Authorization": f"Bearer {config.RESEND_API_KEY}",
                "Content-Type": "application/json",
                "User-Agent": "SecureMsg/1",
            },
        )
        try:
            with urlopen(request, timeout=15) as response:
                if response.status < 200 or response.status >= 300:
                    raise RuntimeError(f"email provider returned HTTP {response.status}")
        except (HTTPError, URLError, TimeoutError) as exc:
            raise RuntimeError("email provider request failed") from exc
        return

    message = EmailMessage()
    message["From"] = config.SMTP_FROM
    message["To"] = recipient
    message["Subject"] = subject
    message.set_content(text)
    context = ssl.create_default_context()
    if config.SMTP_PORT == 465:
        with smtplib.SMTP_SSL(config.SMTP_HOST, config.SMTP_PORT, context=context, timeout=15) as smtp:
            if config.SMTP_USER:
                smtp.login(config.SMTP_USER, config.SMTP_PASSWORD)
            smtp.send_message(message)
        return
    with smtplib.SMTP(config.SMTP_HOST, config.SMTP_PORT, timeout=15) as smtp:
        smtp.ehlo()
        if config.SMTP_STARTTLS:
            smtp.starttls(context=context)
            smtp.ehlo()
        if config.SMTP_USER:
            smtp.login(config.SMTP_USER, config.SMTP_PASSWORD)
        smtp.send_message(message)
