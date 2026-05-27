# External Skills

把你的 skill 目录放在这里。每个 skill 必须包含 `SKILL.md`（带 frontmatter）。

结构示例：

```
skills/
  my-skill/
    SKILL.md             # frontmatter (name/description) + 正文指令
    references/          # 可选：子文档，按需 read_skill_resource
      checklist.md
```

`SKILL.md` frontmatter 最小集合：

```yaml
---
name: my-skill                       # kebab-case, 1-64 字符
description: 一句话描述 skill 用途    # ≤ 1024 字符
---
```

加载顺序：classpath `skills-builtin/` 先扫，本目录后扫；**同名覆盖**内置。

热加载：`POST /actuator/skills` 重新扫描。老 session 持有的 system prompt 已固化，仅新 session 见到最新清单。
