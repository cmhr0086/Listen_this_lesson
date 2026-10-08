from __future__ import annotations

import gzip
import zlib

from starlette.types import ASGIApp, Message, Receive, Scope, Send

CAPABILITIES_HEADER = "X-Listen-Sync-Capabilities"

# Decompressed bodies above this are rejected, so a tiny gzip bomb cannot exhaust memory.
MAX_INFLATED_BYTES = 32 * 1024 * 1024


class GzipRequestMiddleware:
    """Inflates request bodies sent with `Content-Encoding: gzip`; other requests pass through."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = dict(scope.get("headers") or [])
        if headers.get(b"content-encoding", b"").strip().lower() != b"gzip":
            await self.app(scope, receive, send)
            return

        compressed = bytearray()
        while True:
            message = await receive()
            compressed.extend(message.get("body", b""))
            if not message.get("more_body", False):
                break
        try:
            inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
            body = inflater.decompress(bytes(compressed), MAX_INFLATED_BYTES)
            if inflater.unconsumed_tail:
                await _plain_response(send, 413, b"Decompressed request body is too large.")
                return
        except (zlib.error, gzip.BadGzipFile):
            await _plain_response(send, 400, b"Invalid gzip request body.")
            return

        rewritten = [
            (key, value)
            for key, value in scope["headers"]
            if key not in (b"content-encoding", b"content-length")
        ]
        rewritten.append((b"content-length", str(len(body)).encode()))
        delivered = False

        async def inflated_receive() -> Message:
            nonlocal delivered
            if delivered:
                return await receive()
            delivered = True
            return {"type": "http.request", "body": body, "more_body": False}

        await self.app({**scope, "headers": rewritten}, inflated_receive, send)


async def _plain_response(send: Send, status: int, text: bytes) -> None:
    await send({
        "type": "http.response.start",
        "status": status,
        "headers": [(b"content-type", b"text/plain; charset=utf-8"), (b"content-length", str(len(text)).encode())],
    })
    await send({"type": "http.response.body", "body": text})
