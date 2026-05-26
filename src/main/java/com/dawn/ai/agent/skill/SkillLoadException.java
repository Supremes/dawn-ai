package com.dawn.ai.agent.skill;

/**
 * 单个 SKILL.md 解析/校验失败时抛出。
 *
 * <p>{@link SkillRegistry} 必须在扫描循环中捕获本异常，记录 ERROR 并跳过该 skill，
 * 不得使 app 启动失败（错误隔离原则）。
 */
public class SkillLoadException extends RuntimeException {

    public SkillLoadException(String message) {
        super(message);
    }

    public SkillLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
