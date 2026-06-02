"""管理后台服务"""

from typing import Dict, List, Optional
from datetime import datetime, timedelta
import json
import redis.asyncio as redis
from ..config import get_settings


class AdminService:
    """管理后台服务"""

    def __init__(self):
        settings = get_settings()
        self.redis = redis.from_url(settings.redis_url, decode_responses=True)

    async def get_system_stats(self) -> Dict:
        """获取系统统计"""
        # 获取活跃实例数
        instances = []
        async for key in self.redis.scan_iter(match="instances:*"):
            instance = await self.redis.hgetall(key)
            if instance.get("status") == "running":
                instances.append(instance)

        # 获取会话数
        session_count = 0
        async for key in self.redis.scan_iter(match="chat:history:*"):
            session_count += 1

        return {
            "active_instances": len(instances),
            "active_sessions": session_count,
            "timestamp": datetime.now().isoformat(),
        }

    async def get_user_stats(self, user_id: str) -> Dict:
        """获取用户统计"""
        # 获取用户会话数
        session_count = 0
        async for key in self.redis.scan_iter(match=f"chat:history:{user_id}:*"):
            session_count += 1

        # 获取用户画像
        profile_key = f"user:profile:{user_id}"
        profile = await self.redis.get(profile_key)
        profile_data = json.loads(profile) if profile else {}

        return {
            "user_id": user_id,
            "session_count": session_count,
            "profile": profile_data,
        }

    async def get_api_stats(self, hours: int = 24) -> Dict:
        """获取API统计"""
        now = datetime.now()
        start_time = now - timedelta(hours=hours)

        # 这里应该从监控系统获取数据
        # 简化版本返回模拟数据
        return {
            "period_hours": hours,
            "total_requests": 0,
            "successful_requests": 0,
            "failed_requests": 0,
            "average_response_time": 0,
        }

    async def clear_cache(self, pattern: str = "*") -> int:
        """清除缓存"""
        count = 0
        async for key in self.redis.scan_iter(match=pattern):
            await self.redis.delete(key)
            count += 1
        return count

    async def get_config(self) -> Dict:
        """获取配置"""
        settings = get_settings()
        return {
            "chat_model": settings.chat_model,
            "max_steps": settings.max_steps,
            "plan_enabled": settings.plan_enabled,
            "show_steps": settings.show_steps,
        }

    async def update_config(self, updates: Dict) -> Dict:
        """更新配置"""
        # 这里应该更新配置并通知所有实例
        # 简化版本只返回更新
        return updates
