"""Anthropic提供商"""

from typing import List, AsyncIterator
from langchain_core.messages import BaseMessage, HumanMessage, AIMessage, SystemMessage
from ...config import get_settings


class AnthropicProvider(LLMProvider):
    """Anthropic提供商"""

    def __init__(self):
        settings = get_settings()
        self.api_key = settings.anthropic_api_key
        self.model = settings.anthropic_model or "claude-3-sonnet-20240229"

    async def invoke(self, messages: List[BaseMessage], **kwargs) -> str:
        """调用LLM"""
        import anthropic

        client = anthropic.AsyncAnthropic(api_key=self.api_key)

        # 转换消息格式
        anthropic_messages = self._convert_messages(messages)

        response = await client.messages.create(
            model=self.model,
            max_tokens=4096,
            messages=anthropic_messages,
        )

        return response.content[0].text

    async def stream(self, messages: List[BaseMessage], **kwargs) -> AsyncIterator[str]:
        """流式调用LLM"""
        import anthropic

        client = anthropic.AsyncAnthropic(api_key=self.api_key)

        # 转换消息格式
        anthropic_messages = self._convert_messages(messages)

        async with client.messages.stream(
            model=self.model,
            max_tokens=4096,
            messages=anthropic_messages,
        ) as stream:
            async for text in stream.text_stream:
                yield text

    def _convert_messages(self, messages: List[BaseMessage]) -> List[Dict]:
        """转换消息格式"""
        result = []
        for msg in messages:
            if isinstance(msg, SystemMessage):
                # Anthropic使用system参数，不放在messages中
                continue
            elif isinstance(msg, HumanMessage):
                result.append({"role": "user", "content": msg.content})
            elif isinstance(msg, AIMessage):
                result.append({"role": "assistant", "content": msg.content})
        return result

    def get_model_name(self) -> str:
        """获取模型名称"""
        return self.model

    def get_provider_name(self) -> str:
        """获取提供商名称"""
        return "anthropic"
