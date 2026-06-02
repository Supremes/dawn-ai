"""配置测试"""

import pytest
from dawn_ai.config import get_settings, Settings


def test_settings_default():
    """测试默认配置"""
    settings = Settings()
    assert settings.chat_model == "qwen-plus"
    assert settings.max_steps == 10
    assert settings.plan_enabled is True


def test_get_settings_singleton():
    """测试配置单例"""
    settings1 = get_settings()
    settings2 = get_settings()
    assert settings1 is settings2
