"""Memory服务测试"""

import pytest
import asyncio
from unittest.mock import AsyncMock, MagicMock
from dawn_ai.memory import MemoryService


@pytest.fixture
def mock_redis():
    """Mock Redis"""
    redis = AsyncMock()
    redis.rpush = AsyncMock()
    redis.expire = AsyncMock()
    redis.lrange = AsyncMock(return_value=[])
    redis.delete = AsyncMock()
    return redis


@pytest.fixture
def memory_service(mock_redis):
    """创建MemoryService实例"""
    service = MemoryService()
    service.redis = mock_redis
    return service


@pytest.mark.asyncio
async def test_add_message(memory_service, mock_redis):
    """测试添加消息"""
    await memory_service.add_message("session-1", "user", "Hello")

    mock_redis.rpush.assert_called_once()
    mock_redis.expire.assert_called_once()


@pytest.mark.asyncio
async def test_get_history(memory_service, mock_redis):
    """测试获取历史"""
    mock_redis.lrange.return_value = [
        '{"role": "user", "content": "Hello"}',
        '{"role": "assistant", "content": "Hi!"}',
    ]

    history = await memory_service.get_history("session-1")

    assert len(history) == 2
    assert history[0]["role"] == "user"
    assert history[1]["role"] == "assistant"


@pytest.mark.asyncio
async def test_clear_history(memory_service, mock_redis):
    """测试清除历史"""
    await memory_service.clear_history("session-1")

    mock_redis.delete.assert_called_once_with("chat:history:session-1")
