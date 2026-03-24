# Grafana Dashboard — AI Application Metrics 面板说明

> 文件：`grafana/dashboards/spring-boot.json`  
> 新增 Row：**AI Application Metrics**（Panel ID 200–206）

---

## 面板列表

### Panel 201 — Tool Execution Duration P95 (ms)

**类型**：TimeSeries  
**用途**：监控每个工具的 P95 执行耗时，识别慢工具

```promql
histogram_quantile(0.95,
  sum(rate(ai_tool_duration_seconds_bucket[5m])) by (le, tool)
) * 1000
```

**告警建议**：P95 > 3000ms 时触发告警

---

### Panel 202 — Tool Call Rate by Tool & Status

**类型**：TimeSeries  
**用途**：展示各工具的调用频率，区分成功/失败

```promql
sum(rate(ai_tool_calls_total[5m])) by (tool, status)
```

**关注点**：`status=error` 曲线上升代表工具稳定性下降

---

### Panel 203 — Token Consumption Rate (tokens/s)

**类型**：TimeSeries  
**用途**：实时监控 LLM Token 消耗速率，用于成本控制

```promql
# 输入
rate(ai_token_input_total[1m])

# 输出
rate(ai_token_output_total[1m])
```

**扩展**：可配合 OpenAI 单价计算每秒成本（如 input $0.002/1K tokens）

---

### Panel 204 — TaskPlanner Success Rate

**类型**：Stat（颜色阈值）  
**用途**：显示 TaskPlanner 规划成功率

```promql
sum(rate(ai_planner_result_total{status="success"}[5m]))
/ sum(rate(ai_planner_result_total[5m]))
```

| 阈值 | 颜色 |
|------|------|
| < 80% | 🔴 红 |
| 80–95% | 🟡 黄 |
| ≥ 95% | 🟢 绿 |

---

### Panel 205 — RAG Hit Rate

**类型**：Stat（颜色阈值）  
**用途**：显示 RAG 检索命中率，反映知识库覆盖质量

```promql
sum(rate(ai_rag_retrieval_total{result="hit"}[5m]))
/ sum(rate(ai_rag_retrieval_total[5m]))
```

| 阈值 | 颜色 |
|------|------|
| < 50% | 🔴 红 |
| 50–80% | 🟡 黄 |
| ≥ 80% | 🟢 绿 |

**低命中率说明**：需要补充向量库文档，或调整 topK / similarity threshold。

---

### Panel 206 — Error Rate by Type

**类型**：TimeSeries（堆叠）  
**用途**：按错误类型分组展示错误率，快速定位问题域

```promql
sum(rate(ai_error_total[5m])) by (type)
```

| type 值 | 含义 |
|---------|------|
| `config_error` | API Key 未配置 / `AiConfigurationException` |
| `llm_error` | OpenAI 调用失败（网络/超时） |
| `llm_auth_error` | OpenAI 认证失败（Key 无效） |
| `validation_error` | 请求参数校验失败 |
| `internal_error` | 未预期的系统异常 |

---

## 访问方式

```bash
# 启动监控栈
docker-compose up -d prometheus grafana

# 访问 Grafana
open http://localhost:3000
# 账号：admin / admin123

# Dashboard 路径：Dawn AI > AI Application Metrics（最底部 Row）
```
