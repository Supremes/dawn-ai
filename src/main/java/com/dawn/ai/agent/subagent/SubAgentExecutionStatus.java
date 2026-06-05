package com.dawn.ai.agent.subagent;

/**
 * Sub-agent 执行结果的状态。供主 Agent LLM 判断是否需要再派或换策略。
 */
public enum SubAgentExecutionStatus {
    /** 正常跑完，summary 是完整回答 */
    SUCCESS,
    /** 中途失败（步数超限/超时），summary 是已完成步骤的拼接摘要 */
    PARTIAL_SUCCESS,
    /** 未产出任何可用摘要（早期 LLM 错误、配置异常等） */
    FAILED
}
