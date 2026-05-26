package com.dawn.ai.agent.skill;

/**
 * {@link SkillRegistry#readResource} 失败时抛出：资源不存在、路径穿越、IO 错误等。
 *
 * <p>{@code ReadSkillResourceTool} 必须捕获本异常并转换为结构化错误返回值，
 * 避免抛进 agent loop 中断 ReAct 流程。
 */
public class SkillResourceException extends RuntimeException {

    public SkillResourceException(String message) {
        super(message);
    }

    public SkillResourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
