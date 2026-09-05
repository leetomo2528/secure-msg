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
# A client that rotates the caller-supplied identity — a made-up username per
# /login — allocated a fresh key per request and walked the whole LRU table
# out, taking with it every other client's bucket AND its own per-IP mail and
# bcrypt budgets, which are the only bound on an unauthenticated flood. Past
# this ceiling one IP's unseen identities share the scope's identity-less
# bucket instead of evicting anything.
_MAX_KEYS_PER_IP = 200
_ip_key_counts: dict[str, int] = {}


class _BucketTable(OrderedDict):
    """LRU table of (client ip, timestamps), keeping the per-IP counts in step.

    Tests reset the limiter by clearing this table directly, and a count left
    behind would go on folding new identities into the shared bucket for an IP
    that no longer holds a single key.
    """

    def clear(self) -> None:
        super().clear()
        _ip_key_counts.clear()


_buckets: _BucketTable = _BucketTable()
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
    ip = client_ip()
    cutoff = now - window_seconds
    with _lock:
        key = f"{scope}:{ip}:{identity[:64]}"
        if key not in _buckets and _ip_key_counts.get(ip, 0) >= _MAX_KEYS_PER_IP:
            key = f"{scope}:{ip}:"
        entry = _buckets.pop(key, None)
        bucket = deque() if entry is None else entry[1]
        if entry is None:
            _ip_key_counts[ip] = _ip_key_counts.get(ip, 0) + 1
        while bucket and bucket[0] <= cutoff:
            bucket.popleft()
        if len(bucket) >= limit:
            _buckets[key] = (ip, bucket)
            return max(1, math.ceil(bucket[0] + window_seconds - now))
        bucket.append(now)
        _buckets[key] = (ip, bucket)
        while len(_buckets) > _MAX_KEYS:
            evicted_ip = _buckets.popitem(last=False)[1][0]
            remaining = _ip_key_counts.get(evicted_ip, 1) - 1
            if remaining > 0:
                _ip_key_counts[evicted_ip] = remaining
            else:
                _ip_key_counts.pop(evicted_ip, None)
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
