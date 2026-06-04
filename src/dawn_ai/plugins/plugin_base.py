"""插件基类"""

from abc import ABC, abstractmethod
from typing import Dict, Any, List


class PluginBase(ABC):
    """插件基类"""

    @abstractmethod
    def get_name(self) -> str:
        """获取插件名称"""
        pass

    @abstractmethod
    def get_version(self) -> str:
        """获取插件版本"""
        pass

    @abstractmethod
    def get_description(self) -> str:
        """获取插件描述"""
        pass

    @abstractmethod
    def get_tools(self) -> List[Dict]:
        """获取插件提供的工具"""
        pass

    @abstractmethod
    async def initialize(self) -> None:
        """初始化插件"""
        pass

    @abstractmethod
    async def cleanup(self) -> None:
        """清理插件"""
        pass

    def get_metadata(self) -> Dict:
        """获取插件元数据"""
        return {
            "name": self.get_name(),
            "version": self.get_version(),
            "description": self.get_description(),
        }
