"""Sub-Agent注册表"""

from typing import List, Dict, Optional
from dataclasses import dataclass


@dataclass
class SubAgentDefinition:
    """Sub-Agent定义"""
    type: str
    system_prompt: str
    tools: List[str]
    max_steps: int = 10
    description: str = ""


class SubAgentRegistry:
    """Sub-Agent注册表"""

    def __init__(self):
        self._agents: Dict[str, SubAgentDefinition] = {}

    def register(self, definition: SubAgentDefinition) -> None:
        """注册Sub-Agent"""
        self._agents[definition.type] = definition

    def get(self, agent_type: str) -> Optional[SubAgentDefinition]:
        """获取Sub-Agent定义"""
        return self._agents.get(agent_type)

    def list(self) -> List[SubAgentDefinition]:
        """列出所有Sub-Agent"""
        return list(self._agents.values())

    def is_empty(self) -> bool:
        """检查是否为空"""
        return len(self._agents) == 0

    def format_for_prompt(self) -> str:
        """格式化为提示词"""
        if self.is_empty():
            return ""

        lines = ["\n\n## 可派发的子 Agent (Sub-Agent)"]
        lines.append("调用 `dispatch_sub_agent(agent_type, task_description)` 把深度调研/长文档分析这类'重活'派给隔离上下文的子 Agent。")
        lines.append("")
        lines.append("判断准则：")
        lines.append("- ✅ 适合派：需要多轮检索 / 多角度分析 / 长文档综合，单 Agent 上下文会被噪声淹没")
        lines.append("- ❌ 不要派：单次 knowledge_search 1-2 次能搞定的简单问题")
        lines.append("")
        lines.append("可用类型：")

        for defn in self._agents.values():
            lines.append(f"- **{defn.type}**：{defn.description}")

        return "\n".join(lines)
