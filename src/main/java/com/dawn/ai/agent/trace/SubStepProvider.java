package com.dawn.ai.agent.trace;

import java.util.List;

/**
 * 工具返回值的可选实现接口：若工具内部派发了 sub-agent，
 * 实现本接口让 {@link ToolExecutionAspect} 能把 sub-agent 内部步骤
 * 一并填入主 collector 记录的 {@link AgentStep#subSteps()}。
 *
 * <p>这里把 sub-agent 概念以纯 trace 层接口暴露，避免 {@code agent.trace} 包
 * 反向依赖 {@code agent.tools} 或 {@code agent.subagent} 子包。
 */
public interface SubStepProvider {
    List<AgentStep> getSubSteps();
}
