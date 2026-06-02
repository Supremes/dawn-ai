"""RAG服务测试"""

import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from dawn_ai.rag import RagService


@pytest.fixture
def mock_embeddings():
    """Mock Embeddings"""
    embeddings = AsyncMock()
    embeddings.aembed_query = AsyncMock(return_value=[0.1] * 1024)
    return embeddings


@pytest.fixture
def mock_pool():
    """Mock连接池"""
    pool = AsyncMock()
    conn = AsyncMock()
    conn.fetchrow = AsyncMock(return_value={"id": "test-id"})
    conn.fetch = AsyncMock(return_value=[
        {
            "id": "doc-1",
            "content": "测试内容",
            "source": "test",
            "category": "test",
            "score": 0.95,
        }
    ])

    # 创建异步上下文管理器
    class MockAcquire:
        async def __aenter__(self):
            return conn
        async def __aexit__(self, *args):
            pass

    pool.acquire = MockAcquire
    return pool


@pytest.fixture
def rag_service(mock_embeddings, mock_pool):
    """创建RagService实例"""
    service = RagService()
    service.embeddings = mock_embeddings
    service.pool = mock_pool
    return service


@pytest.mark.asyncio
async def test_ingest(rag_service, mock_embeddings, mock_pool):
    """测试文档摄取"""
    result = await rag_service.ingest("测试内容", "test-source", "test")

    assert result["source"] == "test-source"
    assert result["category"] == "test"
    mock_embeddings.aembed_query.assert_called_once_with("测试内容")


@pytest.mark.asyncio
async def test_search(rag_service, mock_embeddings, mock_pool):
    """测试向量检索"""
    results = await rag_service.search("测试查询", top_k=3)

    assert len(results) == 1
    assert results[0]["content"] == "测试内容"
    assert results[0]["score"] == 0.95
    mock_embeddings.aembed_query.assert_called_once_with("测试查询")
