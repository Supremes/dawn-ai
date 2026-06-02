"""Agent编排器 - LangGraph实现"""

from typing import List, Dict, Any, Optional, Callable, TypedDict
from dataclasses import dataclass
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, AIMessage, SystemMessage
from langchain_core.tools import tool
from langgraph.graph import StateGraph, END
from langgraph.prebuilt import ToolNode
from ..config import get_settings
from ..memory import MemoryService
from ..rag import RagService


@dataclass
class AgentStep:
    """Agent执行步骤"""
    step: int
    tool_name: str
    input: Any
    output: Any


@dataclass
class AgentResult:
    """Agent执行结果"""
    answer: str
    steps: List[AgentStep]
    plan: List[Dict]


class AgentState(TypedDict):
    """Agent状态"""
    messages: List[Any]
    steps: List[AgentStep]
    current_step: int


class AgentOrchestrator:
    """Agent编排器 - 使用LangGraph实现ReAct循环"""

    def __init__(self):
        settings = get_settings()
        self.settings = settings
        self.memory_service = MemoryService()
        self.rag_service = RagService()

        # 初始化LLM
        self.llm = ChatOpenAI(
            model=settings.chat_model,
            openai_api_key=settings.openai_api_key,
            openai_api_base=settings.openai_base_url,
            streaming=True,
        )

        # 定义工具
        self.tools = self._create_tools()
        self.llm_with_tools = self.llm.bind_tools(self.tools)

        # 创建Graph
        self.graph = self._create_graph()

    def _create_tools(self) -> List:
        """创建工具列表"""

        @tool
        async def knowledge_search(query: str, top_k: int = 3) -> str:
            """搜索知识库"""
            results = await self.rag_service.search(query, top_k)
            if not results:
                return "未找到相关信息"
            return "\n\n".join(
                f"[来源: {r['source']}]\n{r['content']}" for r in results
            )

        @tool
        def calculator(expression: str) -> str:
            """计算数学表达式"""
            try:
                result = eval(expression)
                return str(result)
            except Exception as e:
                return f"计算错误: {e}"

        return [knowledge_search, calculator]

    def _create_graph(self) -> StateGraph:
        """创建LangGraph"""
        workflow = StateGraph(AgentState)

        # 定义节点
        async def agent_node(state: AgentState) -> AgentState:
            """Agent节点 - 调用LLM"""
            messages = state["messages"]
            response = await self.llm_with_tools.ainvoke(messages)
            return {
                "messages": [*messages, response],
                "steps": state["steps"],
                "current_step": state["current_step"],
            }

        def should_continue(state: AgentState) -> str:
            """判断是否继续执行工具"""
            last_message = state["messages"][-1]
            if hasattr(last_message, "tool_calls") and last_message.tool_calls:
                return "tools"
            return END

        async def tool_node(state: AgentState) -> AgentState:
            """工具节点 - 执行工具调用"""
            last_message = state["messages"][-1]
            steps = state["steps"]

            for tool_call in last_message.tool_calls:
                tool_name = tool_call["name"]
                tool_input = tool_call["args"]

                # 执行工具
                tool_func = next(t for t in self.tools if t.name == tool_name)
                tool_output = await tool_func.ainvoke(tool_input)

                # 记录步骤
                steps.append(
                    AgentStep(
                        step=len(steps) + 1,
                        tool_name=tool_name,
                        input=tool_input,
                        output=tool_output,
                    )
                )

            return {
                "messages": state["messages"],
                "steps": steps,
                "current_step": state["current_step"] + 1,
            }

        # 构建Graph
        workflow.add_node("agent", agent_node)
        workflow.add_node("tools", tool_node)

        workflow.set_entry_point("agent")
        workflow.add_conditional_edges("agent", should_continue)
        workflow.add_edge("tools", "agent")

        return workflow.compile()

    async def chat(self, session_id: str, user_message: str, topic_id: str = None) -> AgentResult:
        """同步聊天"""
        # 获取历史
        history = await self.memory_service.get_history(session_id)

        # 构建消息
        messages = [SystemMessage(content=self._build_system_prompt(topic_id))]
        for msg in history:
            if msg["role"] == "user":
                messages.append(HumanMessage(content=msg["content"]))
            else:
                messages.append(AIMessage(content=msg["content"]))
        messages.append(HumanMessage(content=user_message))

        # 执行Graph
        result = await self.graph.ainvoke({
            "messages": messages,
            "steps": [],
            "current_step": 0,
        })

        # 提取答案
        answer = result["messages"][-1].content

        # 保存到历史
        await self.memory_service.add_message(session_id, "user", user_message)
        await self.memory_service.add_message(session_id, "assistant", answer)

        return AgentResult(
            answer=answer,
            steps=result["steps"],
            plan=[],
        )

    async def stream_chat(
        self,
        session_id: str,
        user_message: str,
        topic_id: str = None,
        callback: Callable = None,
        is_cancelled: Callable = None,
    ) -> None:
        """流式聊天"""
        # 获取历史
        history = await self.memory_service.get_history(session_id)

        # 构建消息
        messages = [SystemMessage(content=self._build_system_prompt(topic_id))]
        for msg in history:
            if msg["role"] == "user":
                messages.append(HumanMessage(content=msg["content"]))
            else:
                messages.append(AIMessage(content=msg["content"]))
        messages.append(HumanMessage(content=user_message))

        # 流式执行
        answer = ""
        steps = []

        async for event in self.graph.astream_events({
            "messages": messages,
            "steps": [],
            "current_step": 0,
        }):
            if is_cancelled and is_cancelled():
                break

            if event["event"] == "on_chat_model_stream":
                chunk = event["data"]["chunk"]
                if chunk.content:
                    answer += chunk.content
                    if callback:
                        callback({"type": "token", "content": chunk.content})

            elif event["event"] == "on_tool_end":
                tool_output = event["data"]["output"]
                tool_name = event["name"]
                steps.append(AgentStep(
                    step=len(steps) + 1,
                    tool_name=tool_name,
                    input=event["data"].get("input"),
                    output=tool_output,
                ))
                if callback:
                    callback({
                        "type": "step",
                        "step": steps[-1],
                    })

        # 保存到历史
        await self.memory_service.add_message(session_id, "user", user_message)
        await self.memory_service.add_message(session_id, "assistant", answer)

        if callback:
            callback({
                "type": "done",
                "answer": answer,
                "steps": steps,
            })

    def _build_system_prompt(self, topic_id: str = None) -> str:
        """构建系统提示"""
        prompt = self.settings.system_prompt
        if topic_id:
            prompt += f"\n\n当前研究主题: {topic_id}"
        return prompt
