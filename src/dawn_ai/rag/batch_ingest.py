"""批量文档摄取"""

from typing import List, Dict
import asyncio
from .rag_service import RagService


class BatchIngestService(RagService):
    """批量文档摄取服务"""

    async def batch_ingest(self, documents: List[Dict], batch_size: int = 10) -> List[Dict]:
        """批量摄取文档"""
        results = []

        for i in range(0, len(documents), batch_size):
            batch = documents[i:i+batch_size]
            batch_results = await self._process_batch(batch)
            results.extend(batch_results)

        return results

    async def _process_batch(self, batch: List[Dict]) -> List[Dict]:
        """处理一批文档"""
        # 批量生成向量
        contents = [doc["content"] for doc in batch]
        embeddings = await self.embeddings.aembed_documents(contents)

        # 批量插入
        results = []
        async with self.pool.acquire() as conn:
            for doc, embedding in zip(batch, embeddings):
                result = await conn.fetchrow(
                    """
                    INSERT INTO documents (content, source, category, embedding)
                    VALUES ($1, $2, $3, $4)
                    RETURNING id
                    """,
                    doc["content"],
                    doc.get("source", ""),
                    doc.get("category", ""),
                    embedding,
                )
                results.append({
                    "id": str(result["id"]),
                    "source": doc.get("source", ""),
                    "category": doc.get("category", ""),
                })

        return results

    async def ingest_from_file(self, file_path: str, source: str, category: str = "") -> List[Dict]:
        """从文件摄取"""
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()

        # 简单的分段（实际应该用更智能的分段策略）
        chunks = self._split_content(content)

        documents = [
            {
                "content": chunk,
                "source": source,
                "category": category,
            }
            for chunk in chunks
        ]

        return await self.batch_ingest(documents)

    def _split_content(self, content: str, chunk_size: int = 1000, overlap: int = 200) -> List[str]:
        """分段内容"""
        chunks = []
        start = 0
        while start < len(content):
            end = start + chunk_size
            chunk = content[start:end]
            chunks.append(chunk)
            start = end - overlap

        return chunks
