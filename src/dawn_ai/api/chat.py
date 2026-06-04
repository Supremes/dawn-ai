"""Chat API"""

import uuid
from typing import Optional
from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from ..agents import AgentOrchestrator

router = APIRouter()
orchestrator = AgentOrchestrator()


class ChatRequest(BaseModel):
    """聊天请求"""
    message: str
    session_id: Optional[str] = None
    topic_id: Optional[str] = None


class ChatResponse(BaseModel):
    """聊天响应"""
    session_id: str
    answer: str
    steps: list
    duration_ms: int


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    """同步聊天"""
    session_id = request.session_id or str(uuid.uuid4())

    try:
        result = await orchestrator.chat(
            session_id=session_id,
            user_message=request.message,
            topic_id=request.topic_id,
        )

        return ChatResponse(
            session_id=session_id,
            answer=result.answer,
            steps=[vars(s) for s in result.steps],
            duration_ms=0,
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/chat/stream")
async def stream_chat(request: ChatRequest):
    """流式聊天"""
    session_id = request.session_id or str(uuid.uuid4())

    async def event_generator():
        """SSE事件生成器"""
        yield f"event: connected\ndata: {{\"sessionId\": \"{session_id}\"}}\n\n"

        try:
            async def callback(event):
                if event["type"] == "token":
                    yield f"event: token\ndata: {{\"content\": \"{event['content']}\"}}\n\n"
                elif event["type"] == "step":
                    yield f"event: step\ndata: {event['step']}\n\n"
                elif event["type"] == "done":
                    yield f"event: done\ndata: {{\"answer\": \"{event['answer']}\"}}\n\n"

            await orchestrator.stream_chat(
                session_id=session_id,
                user_message=request.message,
                topic_id=request.topic_id,
                callback=callback,
            )
        except Exception as e:
            yield f"event: error\ndata: {{\"code\": \"INTERNAL_ERROR\", \"message\": \"{str(e)}\"}}\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
    )
