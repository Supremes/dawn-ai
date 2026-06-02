"""应用配置"""

from functools import lru_cache
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """应用配置"""

    # LLM配置
    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    chat_model: str = "qwen-plus"
    embedding_model: str = "bge-m3-mlx-fp16"
    embedding_base_url: str = ""
    embedding_api_key: str = ""
    embedding_dimensions: int = 1024

    # Redis配置
    redis_url: str = "redis://localhost:6379"

    # PostgreSQL配置
    postgres_url: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/dawn"

    # Agent配置
    system_prompt: str = "You are a helpful AI assistant."
    max_steps: int = 10
    plan_enabled: bool = True
    stream_timeout_ms: int = 120000
    max_subagent_dispatches: int = 3

    # SSE配置
    show_steps: bool = False

    model_config = {
        "env_file": ".env",
        "env_file_encoding": "utf-8",
    }


@lru_cache
def get_settings() -> Settings:
    """获取配置单例"""
    return Settings()
