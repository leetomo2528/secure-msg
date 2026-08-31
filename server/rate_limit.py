"""Small bounded in-process sliding-window limiter for the single worker.

This is not a distributed quota system; it protects the intentionally
single-worker Oracle deployment from trivial bcrypt/message flooding.
"""

from __future__ import annotations

import math
import time
from collections import OrderedDict, deque
from threading import Lock

from flask import current_app, request

_MAX_KEYS = 10_000
_buckets: OrderedDict[str, deque[float]] = OrderedDict()
_lock = Lock()


def client_ip() -> str:
    # Caddy (the only edge proxy) APPENDS the direct client IP to any incoming
    # X-Forwarded-For header, so the rightmost entry is the one Caddy added.
    # Leftmost entries are client-controlled and must never key the limiter —
    # trusting them lets an attacker rotate the spoofed leftmost value to dodge
    # every per-IP limit. If a further upstream proxy is ever added in front of
    # Caddy, revisit this.
    forwarded = request.headers.get("X-Forwarded-For", "")
    entries = [part.strip() for part in forwarded.split(",") if part.strip()]
    value = entries[-1] if entries else (request.remote_addr or "unknown")
    return value[:64]


def check(scope: str, identity: str, limit: int, window_seconds: int) -> int | None:
    """Return Retry-After seconds when denied, otherwise None."""
    if current_app.testing:
        return None
    now = time.monotonic()
    key = f"{scope}:{client_ip()}:{identity[:64]}"
    cutoff = now - window_seconds
    with _lock:
        bucket = _buckets.pop(key, deque())
        while bucket and bucket[0] <= cutoff:
            bucket.popleft()
        if len(bucket) >= limit:
            _buckets[key] = bucket
            return max(1, math.ceil(bucket[0] + window_seconds - now))
        bucket.append(now)
        _buckets[key] = bucket
        while len(_buckets) > _MAX_KEYS:
            _buckets.popitem(last=False)
    return None


def check_ip(scope: str, limit: int, window_seconds: int) -> int | None:
    """Per-IP budget that no caller-supplied value can widen.

    ``check``'s bucket key includes the caller-chosen identity, so a request
    that rotates that value — a made-up username, a stranger's email address —
    opens a brand-new bucket every time and the per-identity limit never
    bites. Endpoints whose cost is paid BEFORE the identity is known (one
    constant-work bcrypt verification per attempt, one outbound mail per
    request) pair their identity bucket with this one, so the total an
    unauthenticated IP can spend stays bounded.
    """
    return check(scope, "", limit, window_seconds)
