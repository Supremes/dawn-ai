"""Agent Marketplace服务"""

from typing import List, Dict, Optional
from dataclasses import dataclass
from datetime import datetime


@dataclass
class AgentListing:
    """Agent列表项"""
    id: str
    name: str
    description: str
    author: str
    version: str
    category: str
    tags: List[str]
    downloads: int
    rating: float
    created_at: str
    updated_at: str


class MarketplaceService:
    """Agent Marketplace服务"""

    def __init__(self):
        self._agents: Dict[str, AgentListing] = {}

    async def register_agent(self, agent: AgentListing) -> None:
        """注册Agent"""
        self._agents[agent.id] = agent

    async def search_agents(self, query: str = None, category: str = None,
                           tags: List[str] = None) -> List[AgentListing]:
        """搜索Agent"""
        results = list(self._agents.values())

        if query:
            query = query.lower()
            results = [
                a for a in results
                if query in a.name.lower() or query in a.description.lower()
            ]

        if category:
            results = [a for a in results if a.category == category]

        if tags:
            results = [
                a for a in results
                if any(tag in a.tags for tag in tags)
            ]

        return results

    async def get_agent(self, agent_id: str) -> Optional[AgentListing]:
        """获取Agent详情"""
        return self._agents.get(agent_id)

    async def get_popular_agents(self, limit: int = 10) -> List[AgentListing]:
        """获取热门Agent"""
        agents = sorted(
            self._agents.values(),
            key=lambda a: a.downloads,
            reverse=True,
        )
        return agents[:limit]

    async def get_top_rated_agents(self, limit: int = 10) -> List[AgentListing]:
        """获取高评分Agent"""
        agents = sorted(
            self._agents.values(),
            key=lambda a: a.rating,
            reverse=True,
        )
        return agents[:limit]

    async def update_stats(self, agent_id: str, downloads: int = 0, rating: float = None) -> None:
        """更新统计信息"""
        agent = self._agents.get(agent_id)
        if agent:
            agent.downloads += downloads
            if rating is not None:
                agent.rating = rating
