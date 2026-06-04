"""RAG服务 - 向量检索"""

from typing import List, Dict, Optional
import asyncpg
from langchain_openai import OpenAIEmbeddings
from ..config import get_settings


class RagService:
    """RAG服务 - 文档摄取和向量检索"""

    def __init__(self):
        settings = get_settings()
        self.embeddings = OpenAIEmbeddings(
            model=settings.embedding_model,
            openai_api_key=settings.embedding_api_key or settings.openai_api_key,
            openai_api_base=settings.embedding_base_url or settings.openai_base_url,
            dimensions=settings.embedding_dimensions,
        )
        self.postgres_url = settings.postgres_url
        self.pool: Optional[asyncpg.Pool] = None

    async def init_pool(self) -> None:
        """初始化连接池"""
        if not self.pool:
            self.pool = await asyncpg.create_pool(self.postgres_url)

    async def ingest(self, content: str, source: str, category: str = "") -> Dict:
        """摄取文档"""
        await self.init_pool()
        embedding = await self.embeddings.aembed_query(content)

        async with self.pool.acquire() as conn:
            result = await conn.fetchrow(
                """
                INSERT INTO documents (content, source, category, embedding)
                VALUES ($1, $2, $3, $4)
                RETURNING id
                """,
                content,
                source,
                category,
                embedding,
            )

        return {"id": str(result["id"]), "source": source, "category": category}

    async def search(self, query: str, top_k: int = 3) -> List[Dict]:
        """向量检索"""
        await self.init_pool()
        embedding = await self.embeddings.aembed_query(query)

        async with self.pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT id, content, source, category,
                       1 - (embedding <=> $1) as score
                FROM documents
                ORDER BY embedding <=> $1
                LIMIT $2
                """,
                embedding,
                top_k,
            )

        return [
            {
                "id": str(row["id"]),
                "content": row["content"],
                "source": row["source"],
                "category": row["category"],
                "score": float(row["score"]),
            }
            for row in rows
        ]
