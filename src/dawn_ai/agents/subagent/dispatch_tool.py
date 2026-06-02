"""Sub-Agent派发工具"""

from typing import Optional, Any
from langchain_core.tools import tool
from .subagent_registry import SubAgentRegistry


class DispatchSubAgentTool:
    """Sub-Agent派发工具"""

    def __init__(self, registry: SubAgentRegistry, orchestrator: Any):
        self.registry = registry
        self.orchestrator = orchestrator
        self.dispatch_count = 0
        self.max_dispatches = 3

    def create_tool(self):
        """创建工具函数"""
        registry = self.registry
        orchestrator = self.orchestrator
        dispatch_counter = {"count": 0}
        max_dispatches = self.max_dispatches

        @tool
        async def dispatch_sub_agent(agent_type: str, task_description: str) -> str:
            """派发任务给子Agent执行。

            Args:
                agent_type: 子Agent类型
                task_description: 任务描述，必须自包含

            Returns:
                子Agent执行结果
            """
            # 检查派发次数限制
            if dispatch_counter["count"] >= max_dispatches:
                return f"错误：已达到最大派发次数限制（{max_dispatches}次）"

            # 获取Agent定义
            definition = registry.get(agent_type)
            if not definition:
                available_types = [d.type for d in registry.list()]
                return f"错误：未知的Agent类型 '{agent_type}'。可用类型：{available_types}"

            # 增加计数
            dispatch_counter["count"] += 1

            # 创建子Agent会话ID
            import uuid
            session_id = f"subagent-{agent_type}-{uuid.uuid4().hex[:8]}"

            # 执行子Agent
            try:
                result = await orchestrator.chat(
                    session_id=session_id,
                    user_message=task_description,
                )
                return result.answer
            except Exception as e:
                return f"子Agent执行失败：{str(e)}"

        return dispatch_sub_agent
