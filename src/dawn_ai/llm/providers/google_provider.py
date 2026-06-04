"""Google提供商"""

from typing import List, AsyncIterator
from langchain_core.messages import BaseMessage
from ...config import get_settings


class GoogleProvider(LLMProvider):
    """Google提供商"""

    def __init__(self):
        settings = get_settings()
        self.api_key = settings.google_api_key
        self.model = settings.google_model or "gemini-pro"

    async def invoke(self, messages: List[BaseMessage], **kwargs) -> str:
        """调用LLM"""
        import google.generativeai as genai

        genai.configure(api_key=self.api_key)
        model = genai.GenerativeModel(self.model)

        # 转换消息格式
        prompt = self._convert_messages(messages)

        response = await model.generate_content_async(prompt)
        return response.text

    async def stream(self, messages: List[BaseMessage], **kwargs) -> AsyncIterator[str]:
        """流式调用LLM"""
        import google.generativeai as genai

        genai.configure(api_key=self.api_key)
        model = genai.GenerativeModel(self.model)

        # 转换消息格式
        prompt = self._convert_messages(messages)

        response = await model.generate_content_async(prompt, stream=True)
        async for chunk in response:
            if chunk.text:
                yield chunk.text

    def _convert_messages(self, messages: List[BaseMessage]) -> str:
        """转换消息格式"""
        parts = []
        for msg in messages:
            if hasattr(msg, 'content'):
                parts.append(msg.content)
        return "\n".join(parts)

    def get_model_name(self) -> str:
        """获取模型名称"""
        return self.model

    def get_provider_name(self) -> str:
        """获取提供商名称"""
        return "google"
