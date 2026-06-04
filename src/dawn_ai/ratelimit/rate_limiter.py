"""限流器"""

from typing import Optional
import time
import redis.asyncio as redis
from ..config import get_settings


class RateLimiter:
    """限流器"""

    def __init__(self):
        settings = get_settings()
        self.redis = redis.from_url(settings.redis_url, decode_responses=True)

    async def is_allowed(self, key: str, limit: int, window: int) -> bool:
        """检查是否允许请求"""
        now = time.time()
        window_start = now - window

        # 使用滑动窗口算法
        pipe = self.redis.pipeline()
        pipe.zremrangebyscore(key, 0, window_start)
        pipe.zadd(key, {str(now): now})
        pipe.zcard(key)
        pipe.expire(key, window)
        results = await pipe.execute()

        request_count = results[2]
        return request_count <= limit

    async def get_remaining(self, key: str, limit: int, window: int) -> int:
        """获取剩余请求次数"""
        now = time.time()
        window_start = now - window

        # 清理过期请求
        await self.redis.zremrangebyscore(key, 0, window_start)

        # 获取当前请求数
        request_count = await self.redis.zcard(key)

        return max(0, limit - request_count)

    async def reset(self, key: str) -> None:
        """重置限流"""
        await self.redis.delete(key)

    async def get_retry_after(self, key: str, limit: int, window: int) -> Optional[int]:
        """获取重试等待时间"""
        now = time.time()
        window_start = now - window

        # 获取最早的请求
        requests = await self.redis.zrangebyscore(key, window_start, now, start=0, num=1)
        if requests:
            oldest_request = float(requests[0])
            retry_after = int(oldest_request + window - now)
            return max(0, retry_after)

        return None
