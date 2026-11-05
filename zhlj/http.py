"""Polite HTTP client.

The WHU sites sit behind a WAF that drops connections after a burst of
requests, so this client serialises requests, adds jitter, and backs off
instead of hammering.
"""

from __future__ import annotations

import http.cookiejar
import json
import logging
import random
import socket
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
)


class FetchError(RuntimeError):
    """The request failed but the site is probably still reachable."""


class BlockedError(FetchError):
    """The site closed the connection repeatedly; stop and retry much later."""


class HttpClient:
    def __init__(
        self,
        *,
        interval: float = 3.0,
        jitter: float = 2.0,
        timeout: float = 25.0,
        max_attempts: int = 3,
        user_agent: str = DEFAULT_USER_AGENT,
        logger: logging.Logger | None = None,
    ) -> None:
        self.interval = interval
        self.jitter = jitter
        self.timeout = timeout
        self.max_attempts = max_attempts
        self.user_agent = user_agent
        self.log = logger or logging.getLogger(__name__)
        self.request_count = 0

        self._cookies = http.cookiejar.CookieJar()
        self._opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self._cookies),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()),
        )
        self._last_request = 0.0

    # ------------------------------------------------------------------
    def get(self, url: str, *, referer: str | None = None,
            headers: dict[str, str] | None = None) -> str:
        return self._request("GET", url, referer=referer, headers=headers)

    def post_json(self, url: str, payload: dict, *, referer: str | None = None,
                  headers: dict[str, str] | None = None) -> str:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        merged = {
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With": "XMLHttpRequest",
        }
        if headers:
            merged.update(headers)
        return self._request("POST", url, body=body, referer=referer, headers=merged)

    def post_form(self, url: str, payload: dict, *, referer: str | None = None,
                  headers: dict[str, str] | None = None) -> str:
        body = urllib.parse.urlencode(payload).encode("utf-8")
        merged = {
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept": "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With": "XMLHttpRequest",
        }
        if headers:
            merged.update(headers)
        return self._request("POST", url, body=body, referer=referer, headers=merged)

    # ------------------------------------------------------------------
    def _request(self, method: str, url: str, *, body: bytes | None = None,
                 referer: str | None = None,
                 headers: dict[str, str] | None = None) -> str:
        base_headers = {
            "User-Agent": self.user_agent,
            "Accept": (
                "text/html,application/xhtml+xml,application/xml;q=0.9,"
                "image/avif,image/webp,*/*;q=0.8"
            ),
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.6",
            "Accept-Encoding": "identity",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
        }
        if referer:
            base_headers["Referer"] = referer
        if headers:
            base_headers.update(headers)

        last_error: Exception | None = None
        for attempt in range(1, self.max_attempts + 1):
            self._throttle()
            request = urllib.request.Request(
                url, data=body, headers=base_headers, method=method
            )
            try:
                with self._opener.open(request, timeout=self.timeout) as response:
                    raw = response.read()
                    charset = response.headers.get_content_charset() or "utf-8"
                    self.request_count += 1
                    return raw.decode(charset, "replace")
            except urllib.error.HTTPError as exc:
                self.request_count += 1
                if exc.code in (429, 500, 502, 503, 504) and attempt < self.max_attempts:
                    last_error = exc
                    self._backoff(attempt, f"HTTP {exc.code}")
                    continue
                raise FetchError(f"HTTP {exc.code} for {url}") from exc
            except urllib.error.URLError as exc:
                last_error = exc
                self._backoff(attempt, f"connection error: {exc.reason}")
            except (ssl.SSLError, ConnectionResetError, ConnectionAbortedError,
                    socket.timeout, TimeoutError, OSError) as exc:
                last_error = exc
                self._backoff(attempt, f"connection dropped: {exc}")

        raise BlockedError(f"giving up on {url}: {last_error}")

    # ------------------------------------------------------------------
    def _throttle(self) -> None:
        wait = self.interval + random.uniform(0, self.jitter)
        elapsed = time.monotonic() - self._last_request
        if elapsed < wait:
            time.sleep(wait - elapsed)
        self._last_request = time.monotonic()

    def _backoff(self, attempt: int, reason: str) -> None:
        delay = 5 * (3 ** (attempt - 1))
        self.log.warning("request failed (%s), retrying in %ss", reason, delay)
        time.sleep(delay)
