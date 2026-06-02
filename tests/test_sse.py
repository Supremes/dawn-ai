"""SSE事件测试"""

import pytest
from dawn_ai.sse import StreamEvent


def test_connected_event():
    """测试连接事件"""
    event = StreamEvent.connected("session-1")
    assert event.event == "connected"
    assert event.data == {"sessionId": "session-1"}
    assert event.session_id == "session-1"


def test_token_event():
    """测试Token事件"""
    event = StreamEvent.token("session-1", "Hello", 5)
    assert event.event == "token"
    assert event.data["content"] == "Hello"
    assert event.data["totalLength"] == 5


def test_step_event():
    """测试步骤事件"""
    step = {"step": 1, "tool_name": "search", "input": "query", "output": "result"}
    event = StreamEvent.step("session-1", step)
    assert event.event == "step"
    assert event.data == step


def test_done_event():
    """测试完成事件"""
    event = StreamEvent.done(
        "session-1",
        "最终回答",
        [{"step": 1, "tool_name": "search"}],
        1000,
    )
    assert event.event == "done"
    assert event.data["answer"] == "最终回答"
    assert event.data["durationMs"] == 1000


def test_error_event():
    """测试错误事件"""
    event = StreamEvent.error("session-1", "ERROR", "错误信息")
    assert event.event == "error"
    assert event.data["code"] == "ERROR"
    assert event.data["message"] == "错误信息"
