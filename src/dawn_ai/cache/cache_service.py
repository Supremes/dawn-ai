"""缓存服务"""

import json
from typing import Any, Optional
import redis.asyncio as redis
from ..config import get_settings


class CacheService:
    """缓存服务"""

    def __init__(self):
        settings = get_settings()
        self.redis = redis.from_url(settings.redis_url, decode_responses=True)
        self.default_ttl = 300  # 5分钟

    async def get(self, key: str) -> Optional[Any]:
        """获取缓存"""
        value = await self.redis.get(key)
        if value:
            return json.loads(value)
        return None

    async def set(self, key: str, value: Any, ttl: int = None) -> None:
        """设置缓存"""
        ttl = ttl or self.default_ttl
        await self.redis.set(key, json.dumps(value), ex=ttl)

    async def delete(self, key: str) -> None:
        """删除缓存"""
        await self.redis.delete(key)

    async def exists(self, key: str) -> bool:
        """检查缓存是否存在"""
        return await self.redis.exists(key)

    async def get_or_set(self, key: str, factory, ttl: int = None) -> Any:
        """获取缓存，如果不存在则创建"""
        value = await self.get(key)
        if value is not None:
            return value

        value = await factory()
        await self.set(key, value, ttl)
        return value

    async def invalidate_pattern(self, pattern: str) -> None:
        """批量删除匹配的缓存"""
        keys = []
        async for key in self.redis.scan_iter(match=pattern):
            keys.append(key)

        if keys:
            await self.redis.delete(*keys)
