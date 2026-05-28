package com.dawn.ai.agent.subagent;

/**
 * Sub-agent 执行入口（纯函数语义）。
 *
 * <p>实现必须保证：
 * <ul>
 *   <li>不读主对话历史，不读 / 不写主 Memory（完全隔离）</li>
 *   <li>{@code parentSessionId} 仅用于 trace 关联（Langfuse parent span / 日志关联），不参与业务状态</li>
 *   <li>异常被 catch 后转成 {@link SubAgentResult}（PARTIAL_SUCCESS / FAILED），
 *       仅 {@link com.dawn.ai.exception.AiConfigurationException} 等不可恢复异常向外抛</li>
 *   <li>{@link SubAgentResult#subSteps} 不进入主 {@link com.dawn.ai.agent.trace.StepCollector}</li>
 * </ul>
 */
public interface SubAgentExecutor {

    /**
     * @param type             已在 {@link SubAgentRegistry} 注册的 sub-agent 类型名
     * @param taskDescription  主 Agent 给出的自包含任务描述（sub-agent 看不到对话历史）
     * @param parentSessionId  主请求 sessionId，仅用于派生 trace ID 与日志关联
     */
}
