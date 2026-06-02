"""任务规划器"""

from typing import List, Dict, Optional
from dataclasses import dataclass
from langchain_openai import ChatOpenAI
from langchain_core.messages import SystemMessage, HumanMessage
from ...config import get_settings


@dataclass
class PlanStep:
    """计划步骤"""
    step: int
    action: str
    reason: str
    tool: Optional[str] = None


@dataclass
class PlannerResult:
    """规划结果"""
    steps: List[PlanStep]
    reasoning: str = ""

    @classmethod
    def empty(cls) -> "PlannerResult":
        """创建空结果"""
        return cls(steps=[], reasoning="")


class TaskPlanner:
    """任务规划器"""

    def __init__(self):
        settings = get_settings()
        self.llm = ChatOpenAI(
            model=settings.chat_model,
            openai_api_key=settings.openai_api_key,
            openai_api_base=settings.openai_base_url,
            temperature=0,
        )

    async def plan(self, user_message: str, tool_descriptions: List[str]) -> PlannerResult:
        """生成执行计划"""
        # 构建提示词
        tool_list = "\n".join(f"- {desc}" for desc in tool_descriptions)
        prompt = f"""你是一个任务规划专家。根据用户的问题，制定一个执行计划。

可用工具：
{tool_list}

用户问题：{user_message}

请制定一个简洁的执行计划，每个步骤包含：
1. step: 步骤编号
2. action: 要执行的动作
3. reason: 执行原因
4. tool: 使用的工具（如果有）

以JSON格式返回计划：
{{"steps": [{{"step": 1, "action": "...", "reason": "...", "tool": "..."}}], "reasoning": "规划思路"}}
"""

        try:
            response = await self.llm.ainvoke([
                SystemMessage(content="你是一个任务规划专家，擅长分解复杂任务。"),
                HumanMessage(content=prompt),
            ])

            # 解析响应
            import json
            content = response.content
            # 提取JSON部分
            start = content.find("{")
            end = content.rfind("}") + 1
            if start != -1 and end != -1:
                data = json.loads(content[start:end])
                steps = [
                    PlanStep(
                        step=s.get("step", i+1),
                        action=s.get("action", ""),
                        reason=s.get("reason", ""),
                        tool=s.get("tool"),
                    )
                    for i, s in enumerate(data.get("steps", []))
                ]
                return PlannerResult(
                    steps=steps,
                    reasoning=data.get("reasoning", ""),
                )
        except Exception as e:
            print(f"规划失败: {e}")

        return PlannerResult.empty()
