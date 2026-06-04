"""LLM提供商基类"""

from abc import ABC, abstractmethod
from typing import List, Dict, Any, AsyncIterator
from langchain_core.messages import BaseMessage


class LLMProvider(ABC):
    """LLM提供商基类"""

    @abstractmethod
    async def invoke(self, messages: List[BaseMessage], **kwargs) -> str:
        """调用LLM"""
        pass

    @abstractmethod
    async def stream(self, messages: List[BaseMessage], **kwargs) -> AsyncIterator[str]:
        """流式调用LLM"""
        pass

    @abstractmethod
    def get_model_name(self) -> str:
        """获取模型名称"""
        pass

    @abstractmethod
    def get_provider_name(self) -> str:
        """获取提供商名称"""
        pass
