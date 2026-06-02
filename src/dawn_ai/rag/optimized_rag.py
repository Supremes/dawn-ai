"""优化的RAG服务"""

from typing import List, Dict, AsyncIterator
import asyncio
from .rag_service import RagService


class OptimizedRagService(RagService):
    """优化的RAG服务"""

    async def search_stream(self, query: str, top_k: int = 3) -> AsyncIterator[Dict]:
        """流式检索"""
        # 获取向量
        embedding = await self.embeddings.aembed_query(query)

        # 分批检索
        batch_size = 5
        for i in range(0, top_k, batch_size):
            batch_top_k = min(batch_size, top_k - i)

            async with self.pool.acquire() as conn:
                rows = await conn.fetch(
                    """
                    SELECT id, content, source, category,
                           1 - (embedding <=> $1) as score
                    FROM documents
                    ORDER BY embedding <=> $1
                    LIMIT $2 OFFSET $3
                    """,
                    embedding,
                    batch_top_k,
                    i,
                )

            for row in rows:
                yield {
                    "id": str(row["id"]),
                    "content": row["content"],
                    "source": row["source"],
                    "category": row["category"],
                    "score": float(row["score"]),
                }

    async def batch_search(self, queries: List[str], top_k: int = 3) -> List[List[Dict]]:
        """批量检索"""
        tasks = [self.search(q, top_k) for q in queries]
        return await asyncio.gather(*tasks)

    async def search_with_cache(self, query: str, top_k: int = 3, cache_ttl: int = 300) -> List[Dict]:
        """带缓存的检索"""
        cache_key = f"rag:cache:{query}:{top_k}"

        # 检查缓存
        cached = await self.redis.get(cache_key)
        if cached:
            import json
            return json.loads(cached)

        # 执行检索
        results = await self.search(query, top_k)

        # 缓存结果
        import json
        await self.redis.set(cache_key, json.dumps(results), ex=cache_ttl)

        return results
