"""FastAPI应用"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from .chat import router as chat_router
from .rag import router as rag_router


def create_app() -> FastAPI:
    """创建FastAPI应用"""
    app = FastAPI(
        title="Dawn AI",
        description="AI Agent应用 - Python LangChain/LangGraph实现",
        version="1.0.0",
    )

    # CORS配置
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # 注册路由
    app.include_router(chat_router, prefix="/api/v1")
    app.include_router(rag_router, prefix="/api/v1")

    return app
