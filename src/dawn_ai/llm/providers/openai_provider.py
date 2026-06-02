"""OpenAI提供商"""

from typing import List, AsyncIterator
from langchain_core.messages import BaseMessage
from langchain_openai import ChatOpenAI
from ..llm_provider import LLMProvider
from ...config import get_settings


class OpenAIProvider(LLMProvider):
    """OpenAI提供商"""

    def __init__(self):
        settings = get_settings()
        self.llm = ChatOpenAI(
            model=settings.chat_model,
            openai_api_key=settings.openai_api_key,
            openai_api_base=settings.openai_base_url,
        )

    async def invoke(self, messages: List[BaseMessage], **kwargs) -> str:
        """调用LLM"""
        response = await self.llm.ainvoke(messages, **kwargs)
        return response.content

    async def stream(self, messages: List[BaseMessage], **kwargs) -> AsyncIterator[str]:
        """流式调用LLM"""
        async for chunk in self.llm.astream(messages, **kwargs):
            if chunk.content:
                yield chunk.content

    def get_model_name(self) -> str:
        """获取模型名称"""
        return self.llm.model_name

    def get_provider_name(self) -> str:
        """获取提供商名称"""
        return "openai"
