"""分布式部署服务"""

from typing import Dict, List, Optional
import asyncio
import json
import redis.asyncio as redis
from ..config import get_settings


class DeploymentService:
    """分布式部署服务"""

    def __init__(self):
        settings = get_settings()
        self.redis = redis.from_url(settings.redis_url, decode_responses=True)
        self.instance_id = settings.instance_id or "default"
        self.heartbeat_interval = 30  # 30秒

    async def register_instance(self) -> None:
        """注册实例"""
        key = f"instances:{self.instance_id}"
        await self.redis.hset(key, mapping={
            "id": self.instance_id,
            "started_at": str(asyncio.get_event_loop().time()),
            "status": "running",
        })
        await self.redis.expire(key, 60)

    async def heartbeat(self) -> None:
        """发送心跳"""
        key = f"instances:{self.instance_id}"
        await self.redis.hset(key, "last_heartbeat", str(asyncio.get_event_loop().time()))
        await self.redis.expire(key, 60)

    async def get_active_instances(self) -> List[Dict]:
        """获取活跃实例"""
        instances = []
        async for key in self.redis.scan_iter(match="instances:*"):
            instance = await self.redis.hgetall(key)
            if instance.get("status") == "running":
                instances.append(instance)
        return instances

    async def distribute_task(self, task_type: str, task_data: Dict) -> Optional[str]:
        """分发任务"""
        # 获取活跃实例
        instances = await self.get_active_instances()
        if not instances:
            return None

        # 简单的轮询分发
        import random
        instance = random.choice(instances)

        # 添加到任务队列
        queue_key = f"tasks:{instance['id']}"
        await self.redis.rpush(queue_key, json.dumps({
            "type": task_type,
            "data": task_data,
        }))

        return instance["id"]

    async def get_tasks(self) -> List[Dict]:
        """获取当前实例的任务"""
        queue_key = f"tasks:{self.instance_id}"
        tasks = []

        while True:
            task = await self.redis.lpop(queue_key)
            if not task:
                break
            tasks.append(json.loads(task))

        return tasks

    async def start_heartbeat(self) -> None:
        """启动心跳任务"""
        while True:
            await self.heartbeat()
            await asyncio.sleep(self.heartbeat_interval)
