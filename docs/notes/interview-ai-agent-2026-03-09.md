# 🎙️ AI Agent 面试记录

> **日期：** 2026-03-09  
> **方向：** AI Agent 工程化  
> **级别：** P9 大厂压力面试模拟

---

## 问题 1：AI Agent 与普通 LLM 调用的本质区别

### ❓ 题目
请解释 AI Agent 与普通 LLM 调用（直接 ChatCompletion）的本质区别是什么？

### 💬 我的回答
AI Agent 会设定一个终止条件，符合终止条件后 Agent 才认为任务已执行完毕，可类比为 `while` 循环。

### 📋 面试官点评
**得分：20/100 — 答对了控制流骨架，但缺失 3 个本质维度。**

#### ✅ 答对的点
Agent 的 ReAct 循环控制结构：

```java
while (!terminationCondition()) {
    Observation obs = perceive(environment);
    Action action  = llm.reason(obs, history);
    Result result  = executeTool(action);
    history.add(result);
}
return synthesize(history);
```

#### ❌ 漏掉的 3 个本质维度

| 维度 | 说明 |
|------|------|
| **自主性（Autonomy）** | Agent 可自主决策调哪个 Tool、调几次、以何顺序；LLM 调用是无状态的单次问答 |
| **状态持久化（Memory）** | Agent 有跨轮次的短期/长期记忆；LLM 每次调用上下文归零 |
| **感知-决策-行动闭环** | Agent 能将 Tool 执行结果反馈回 LLM 继续推理，形成真正的闭环 |

---

## 问题 2：Agent 兜底终止机制设计

### ❓ 题目
① 如果 LLM 出现幻觉导致永远认为任务未完成，while 循环会怎样？  
② 除 LLM 主动判断终止外，还能设计哪些兜底终止机制？请至少说出 3 种，并说明对应的传统后端工程模式。

### 💬 我的回答
- 最大循环次数（`maxIterations`）
- 最大运行时间（`timeout`）

### 📋 面试官点评
**得分：50/100 — 答对核心，但工程深度不够。**

#### ✅ 答对的点
- 最大迭代次数
- 超时机制

#### ❌ 漏掉的 3 种高阶兜底机制

| 兜底机制 | 原理 | 对应后端工程模式 |
|---------|------|----------------|
| **重复动作检测** | 检测 LLM 是否连续 N 轮调用同一 Tool + 同一参数 | 死锁检测 / `ThreadMXBean.findDeadlockedThreads()` |
| **Token 预算熔断** | 累计消耗 Token 超阈值强制终止，防止费用失控 | Resilience4j `CircuitBreaker` 调用次数阈值 |
| **语义收敛检测** | 检测相邻两轮 LLM 输出语义相似度，高度重复则判定"原地打转" | 幂等性检测 + 向量余弦相似度判停 |

#### 🔧 工程级终止守卫代码骨架

```java
// Java 17 + Spring Boot 3
public class AgentTerminationGuard {

    private final int maxIterations;        // 兜底①：最大轮次
    private final Duration maxDuration;     // 兜底②：最大运行时间
    private final int maxTokenBudget;       // 兜底③：Token 预算熔断
    private final double repeatThreshold;   // 兜底④：语义重复检测阈值

    private int iterations = 0;
    private int totalTokens = 0;
    private String lastAction = "";
    private final Instant startTime = Instant.now();

    public TerminationResult check(String currentAction, int tokenUsed) {
        iterations++;
        totalTokens += tokenUsed;

        if (iterations >= maxIterations)
            return TerminationResult.of(FORCE_STOP, "Max iterations reached");

        if (Duration.between(startTime, Instant.now()).compareTo(maxDuration) > 0)
            return TerminationResult.of(FORCE_STOP, "Timeout exceeded");

        if (totalTokens >= maxTokenBudget)
            return TerminationResult.of(FORCE_STOP, "Token budget exhausted");

        if (currentAction.equals(lastAction))   // 简化版重复检测
            return TerminationResult.of(FORCE_STOP, "Repeated action detected");

        lastAction = currentAction;
        return TerminationResult.of(CONTINUE, "OK");
    }
}
```

---

## 问题 3：Agent 记忆遗忘问题（待作答）

### ❓ 题目
在 `dawn-ai` 项目中，`MemoryService` 用 Redis List 存储对话历史，设了 `MAX_HISTORY = 20`。

① 为什么不能无限存？上下文越长不是越好吗？这背后涉及 LLM 的哪个核心物理限制？

② 当对话超过 20 条后，早期信息被 `leftPop` 丢弃。用户第 1 轮说"我叫张三，是 VIP 用户"，第 25 轮问"帮我查我的订单"，Agent 还认识他吗？

③ 你会怎么解决这个"记忆遗忘"问题？请给出架构方案。

### 💬 我的回答
> *(待补充)*

### 📋 标准答案要点（面试官提示）
> 涉及 **短期记忆 vs 长期记忆** 的架构分层，以及一个熟悉的存储组件用于长期记忆持久化。

---

## 📌 知识盲区总结

| 题目 | 盲区点 |
|------|--------|
| Q1 | Agent 的自主性、状态持久化、感知-决策-行动闭环 |
| Q2 | 重复动作检测、Token 预算熔断、语义收敛检测 |
| Q3 | 短期/长期记忆分层架构（待深入） |

---

## 🔗 关联代码
- 项目路径：`/mnt/f/work/code/dawn-ai`
- 核心类：`AgentOrchestrator.java`、`MemoryService.java`
