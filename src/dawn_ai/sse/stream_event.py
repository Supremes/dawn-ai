"""SSE事件定义"""

from dataclasses import dataclass
from typing import Any, Optional


@dataclass
class StreamEvent:
    """SSE流式事件"""
    event: str
    data: Any
    session_id: str
    seq: int = 0

    @classmethod
    def connected(cls, session_id: str) -> "StreamEvent":
        """连接事件"""
        return cls(
            event="connected",
            data={"sessionId": session_id},
            session_id=session_id,
        )

    @classmethod
    def token(cls, session_id: str, content: str, total_length: int) -> "StreamEvent":
        """Token事件"""
        return cls(
            event="token",
            data={"content": content, "totalLength": total_length},
            session_id=session_id,
        )

    @classmethod
    def step(cls, session_id: str, step: Any) -> "StreamEvent":
        """步骤事件"""
        return cls(
            event="step",
            data=step,
            session_id=session_id,
        )

    @classmethod
    def done(cls, session_id: str, answer: str, steps: list, duration_ms: int) -> "StreamEvent":
        """完成事件"""
        return cls(
            event="done",
            data={
                "answer": answer,
                "steps": steps,
                "durationMs": duration_ms,
            },
            session_id=session_id,
        )

    @classmethod
    def error(cls, session_id: str, code: str, message: str) -> "StreamEvent":
        """错误事件"""
        return cls(
            event="error",
            data={"code": code, "message": message},
            session_id=session_id,
        )
