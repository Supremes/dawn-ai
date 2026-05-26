---
name: code-review-zh
description: 中文 Java 代码 review 风格指南。当用户请求 review/审查/挑毛病一段 Java 代码、或贴出 Java 片段希望得到改进建议时调用。指导模型按"先找正确性问题、再找可读性/命名、再给改进建议"的顺序输出，所有反馈以中文呈现。详细 review checklist 见 references/checklist.md（按需调用 read_skill_resource 加载）。
---

# Code Review (中文)

## 何时使用本 Skill

当用户的请求满足以下任一条件时使用：

- 明确包含「review / 审查 / 看看这段代码 / 帮我挑毛病 / code review」等关键词
- 贴出超过 5 行的 Java 代码片段并希望得到改进建议
- 询问代码质量、命名、错误处理等评价性问题

## Review 输出结构

按以下顺序组织回复，每段都用中文：

### 1. 正确性（Correctness）
列出会导致 bug、空指针、并发问题、资源泄漏的问题。这一段最重要，必须先讲。若无问题，明确写「未发现正确性问题」。

### 2. 可读性与命名（Readability & Naming）
- 变量/方法/类命名是否表意清晰
- 方法长度、嵌套深度是否过大
- 是否有 magic number、注释 vs 自解释代码的平衡

### 3. 错误处理与边界（Error Handling）
- 异常类型是否合适（受检 vs 非受检）
- 是否在边界（null、空集合、负数、超长字符串）做了合理处理
- 资源（IO、连接）是否在 try-with-resources 内

### 4. 改进建议（Suggestions）
给出可落地的修改建议，**用代码片段示意**，不要只描述。

## 关键原则

- **批评对事不对人**：永远讨论代码，不评价作者
- **优先级排序**：正确性 > 可读性 > 性能 > 风格
- **建议要可执行**：「这里应该更好」是 AI slop；「这里把 `if-else` 改成 `Optional.map` 链能消除 null 检查」才是有用反馈
- **承认不确定**：信息不足时主动问「这个方法的调用方是什么场景？」而不是猜

## 进一步阅读

详细的 review checklist（按类别细分的检查项清单，约 30 项）见同目录下 `references/checklist.md`。需要严格地逐项检查时，调用 `read_skill_resource(skill="code-review-zh", path="references/checklist.md")` 加载。
