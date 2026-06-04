"""API测试"""

import pytest
from fastapi.testclient import TestClient
from unittest.mock import AsyncMock, patch
from dawn_ai.api import create_app


@pytest.fixture
def client():
    """创建测试客户端"""
    app = create_app()
    return TestClient(app)


def test_health_check(client):
    """测试健康检查"""
    response = client.get("/")
    assert response.status_code == 404  # 根路径未定义


@patch("dawn_ai.api.chat.orchestrator")
def test_chat_endpoint(mock_orchestrator, client):
    """测试Chat API"""
    mock_orchestrator.chat = AsyncMock(return_value=type("AgentResult", (), {
        "answer": "测试回答",
        "steps": [],
    })())

    response = client.post(
        "/api/v1/chat",
        json={
            "message": "测试消息",
            "session_id": "test-session",
        },
    )

    assert response.status_code == 200
    data = response.json()
    assert data["session_id"] == "test-session"
    assert data["answer"] == "测试回答"


@patch("dawn_ai.api.rag.rag_service")
def test_rag_ingest(mock_rag_service, client):
    """测试RAG摄取API"""
    mock_rag_service.ingest = AsyncMock(return_value={
        "id": "test-id",
        "source": "test-source",
        "category": "test",
    })

    response = client.post(
        "/api/v1/rag/ingest",
        json={
            "content": "测试内容",
            "source": "test-source",
            "category": "test",
        },
    )

    assert response.status_code == 200
    data = response.json()
    assert data["id"] == "test-id"
    assert data["source"] == "test-source"


@patch("dawn_ai.api.rag.rag_service")
def test_rag_search(mock_rag_service, client):
    """测试RAG搜索API"""
    mock_rag_service.search = AsyncMock(return_value=[
        {
            "id": "doc-1",
            "content": "测试内容",
            "source": "test",
            "category": "test",
            "score": 0.95,
        }
    ])

    response = client.get(
        "/api/v1/rag/search",
        params={"query": "测试查询", "top_k": 3},
    )

    assert response.status_code == 200
    data = response.json()
    assert len(data) == 1
    assert data[0]["content"] == "测试内容"
