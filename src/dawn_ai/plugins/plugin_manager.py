"""插件管理器"""

from typing import Dict, List, Optional
import importlib
from pathlib import Path
from .plugin_base import PluginBase


class PluginManager:
    """插件管理器"""

    def __init__(self):
        self._plugins: Dict[str, PluginBase] = {}
        self._initialized: bool = False

    async def load_plugins(self, plugin_dir: str = None) -> None:
        """加载插件"""
        if not plugin_dir:
            plugin_dir = Path(__file__).parent / "builtin"

        plugin_path = Path(plugin_dir)
        if not plugin_path.exists():
            return

        for item in plugin_path.iterdir():
            if item.is_dir() and (item / "__init__.py").exists():
                await self._load_plugin(item)

        self._initialized = True

    async def _load_plugin(self, plugin_path: Path) -> None:
        """加载单个插件"""
        try:
            # 动态导入插件模块
            module_name = f"dawn_ai.plugins.builtin.{plugin_path.name}"
            module = importlib.import_module(module_name)

            # 查找插件类
            for attr_name in dir(module):
                attr = getattr(module, attr_name)
                if (isinstance(attr, type) and
                    issubclass(attr, PluginBase) and
                    attr != PluginBase):

                    # 实例化插件
                    plugin = attr()
                    await plugin.initialize()

                    self._plugins[plugin.get_name()] = plugin
                    print(f"加载插件: {plugin.get_name()} v{plugin.get_version()}")

        except Exception as e:
            print(f"加载插件失败 {plugin_path.name}: {e}")

    def get_plugin(self, name: str) -> Optional[PluginBase]:
        """获取插件"""
        return self._plugins.get(name)

    def list_plugins(self) -> List[Dict]:
        """列出所有插件"""
        return [plugin.get_metadata() for plugin in self._plugins.values()]

    def get_all_tools(self) -> List[Dict]:
        """获取所有插件的工具"""
        tools = []
        for plugin in self._plugins.values():
            tools.extend(plugin.get_tools())
        return tools

    async def cleanup(self) -> None:
        """清理所有插件"""
        for plugin in self._plugins.values():
            try:
                await plugin.cleanup()
            except Exception as e:
                print(f"清理插件失败 {plugin.get_name()}: {e}")

        self._plugins.clear()
