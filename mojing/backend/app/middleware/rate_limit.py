"""API 限流中间件。

基于 IP 和路径的简单内存限流，防止恶意请求。
"""

import time
from collections import defaultdict
from typing import Tuple

from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.types import ASGIApp

from ..logger import logger

# 限流配置: (窗口秒数, 最大请求数)
_RATE_LIMITS: dict[str, Tuple[int, int]] = {
    "default": (60, 300),
    "generate": (60, 60),
    "import": (300, 5),
}


class RateLimitMiddleware(BaseHTTPMiddleware):
    """基于内存的滑动窗口限流中间件。"""

    def __init__(self, app: ASGIApp):
        super().__init__(app)
        self._windows: dict[str, list[float]] = defaultdict(list)

    def _get_bucket(self, request: Request) -> str | None:
        path = request.url.path
        if "/generate/stream" in path:
            return "generate"
        if "/import" in path:
            return "import"
        return "default"

    def _is_limited(self, bucket: str) -> bool:
        now = time.time()
        window, max_req = _RATE_LIMITS.get(bucket, _RATE_LIMITS["default"])
        timestamps = self._windows[bucket]
        cutoff = now - window
        self._windows[bucket] = [t for t in timestamps if t > cutoff]
        if len(self._windows[bucket]) >= max_req:
            return True
        self._windows[bucket].append(now)
        return False

    async def dispatch(self, request: Request, call_next):
        bucket = self._get_bucket(request)
        if bucket and self._is_limited(bucket):
            logger.warning("请求频率超限", extra={"path": request.url.path, "ip": request.client.host if request.client else "unknown"})
            return Response(
                content='{"detail":"请求频率超限，请稍后再试"}',
                status_code=429,
                media_type="application/json",
                headers={"Retry-After": "60"},
            )
        return await call_next(request)
