"""Langfuse集成"""

from typing import Optional, Dict, Any
from langfuse import Langfuse
from ..config import get_settings


class LangfuseIntegration:
    """Langfuse可观测性集成"""

    def __init__(self):
        settings = get_settings()
        self.client: Optional[Langfuse] = None

        # 检查配置
        if hasattr(settings, 'langfuse_public_key') and settings.langfuse_public_key:
            self.client = Langfuse(
                public_key=settings.langfuse_public_key,
                secret_key=settings.langfuse_secret_key,
                host=settings.langfuse_host or "https://cloud.langfuse.com",
            )

    def is_enabled(self) -> bool:
        """检查是否启用"""
        return self.client is not None

    def create_trace(self, name: str, session_id: str, user_id: str = None) -> Optional[Any]:
        """创建追踪"""
        if not self.is_enabled():
            return None

        return self.client.trace(
            name=name,
            session_id=session_id,
            user_id=user_id,
        )

    def create_span(self, trace_id: str, name: str, metadata: Dict = None) -> Optional[Any]:
        """创建Span"""
        if not self.is_enabled():
            return None

        return self.client.span(
            trace_id=trace_id,
            name=name,
            metadata=metadata or {},
        )

    def create_generation(self, trace_id: str, name: str, model: str,
                         input: Any, output: Any = None, metadata: Dict = None) -> Optional[Any]:
        """创建Generation"""
        if not self.is_enabled():
            return None

        return self.client.generation(
            trace_id=trace_id,
            name=name,
            model=model,
            input=input,
            output=output,
            metadata=metadata or {},
        )

    def flush(self) -> None:
        """刷新数据"""
        if self.is_enabled():
            self.client.flush()
