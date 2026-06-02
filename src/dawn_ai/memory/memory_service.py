"""Memory服务 - Redis实现"""

import json
from typing import List, Dict
import redis.asyncio as redis
from ..config import get_settings


class MemoryService:
    """Memory服务 - 管理对话历史"""

    def __init__(self):
        settings = get_settings()
        self.redis = redis.from_url(settings.redis_url, decode_responses=True)
        self.ttl = 3600 * 24  # 24小时过期

    async def add_message(self, session_id: str, role: str, content: str) -> None:
        """添加消息到历史"""
        key = f"chat:history:{session_id}"
        message = json.dumps({"role": role, "content": content})
        await self.redis.rpush(key, message)
        await self.redis.expire(key, self.ttl)

    async def get_history(self, session_id: str) -> List[Dict[str, str]]:
        """获取对话历史"""
        key = f"chat:history:{session_id}"
        messages = await self.redis.lrange(key, 0, -1)
        return [json.loads(msg) for msg in messages]

    async def clear_history(self, session_id: str) -> None:
        """清除对话历史"""
        key = f"chat:history:{session_id}"
        await self.redis.delete(key)
