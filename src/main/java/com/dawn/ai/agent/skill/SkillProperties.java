package com.dawn.ai.agent.skill;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.skills")
@Getter
@Setter
public class SkillProperties {

    /** 外挂 skill 目录的根路径。同名 skill 会覆盖 classpath 内置示例。 */
    private String path = "./skills";

    /** 是否启用 Skill 体系；关闭后 SkillRegistry 为空，system prompt 不注入 Skills 段。 */
    private boolean enabled = true;
}
