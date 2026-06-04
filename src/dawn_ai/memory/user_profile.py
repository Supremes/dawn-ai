"""用户画像服务"""

from typing import Dict, List, Optional
import json
from datetime import datetime
from ..memory import MemoryService


class UserProfileService:
    """用户画像服务"""

    def __init__(self, memory_service: MemoryService):
        self.memory_service = memory_service

    async def get_profile(self, session_id: str) -> Dict:
        """获取用户画像"""
        key = f"user:profile:{session_id}"
        profile = await self.memory_service.redis.get(key)
        if profile:
            return json.loads(profile)
        return {}

    async def update_profile(self, session_id: str, updates: Dict) -> None:
        """更新用户画像"""
        key = f"user:profile:{session_id}"
        profile = await self.get_profile(session_id)
        profile.update(updates)
        profile["updated_at"] = datetime.now().isoformat()
        await self.memory_service.redis.set(key, json.dumps(profile))
        await self.memory_service.redis.expire(key, 86400 * 30)  # 30天过期

    async def add_interest(self, session_id: str, interest: str) -> None:
        """添加兴趣"""
        profile = await self.get_profile(session_id)
        interests = profile.get("interests", [])
        if interest not in interests:
            interests.append(interest)
            await self.update_profile(session_id, {"interests": interests})

    async def get_interests(self, session_id: str) -> List[str]:
        """获取兴趣列表"""
        profile = await self.get_profile(session_id)
        return profile.get("interests", [])

    async def add_preference(self, session_id: str, key: str, value: str) -> None:
        """添加偏好"""
        profile = await self.get_profile(session_id)
        preferences = profile.get("preferences", {})
        preferences[key] = value
        await self.update_profile(session_id, {"preferences": preferences})

    async def get_preferences(self, session_id: str) -> Dict:
        """获取偏好"""
        profile = await self.get_profile(session_id)
        return profile.get("preferences", {})

    async def format_for_system_prompt(self, session_id: str) -> str:
        """格式化为系统提示"""
        profile = await self.get_profile(session_id)
        if not profile:
            return ""

        lines = ["\n\n【用户画像】"]

        interests = profile.get("interests", [])
        if interests:
            lines.append(f"兴趣：{', '.join(interests)}")

        preferences = profile.get("preferences", {})
        if preferences:
            lines.append("偏好：")
            for k, v in preferences.items():
                lines.append(f"  - {k}: {v}")

        return "\n".join(lines)
