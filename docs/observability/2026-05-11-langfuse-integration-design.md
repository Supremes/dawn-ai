# Langfuse 可观测性集成 — 设计规格文档

- **日期**：2026-05-11
- **分支**：`feat/langfuse-integration`
- **状态**：草稿（待规格评审 + 用户确认）
- **负责人**：Supremes

## 1. 目标

通过在现有 `docker-compose.yml` 中自托管 **Langfuse v3**，为 dawn-ai 提供端到端 LLM 可观测性，并将所有 Spring AI Observation Span（chat、embedding、vector store、advisor、tool-calling）通过 **OTLP/HTTP** 协议推送到 Langfuse。Langfuse UI 中的 Sessions 视图须按 `AiInteractionContext` 中已有的 `sessionId` 进行聚合。

## 2. 非目标（Non-Goals）

- 不涉及生产环境部署拓扑（本规格仅覆盖本地开发；生产加固为独立工作流）。
- 不使用 Langfuse 的 Prompt 管理、Evaluation 或 Playground 功能，仅使用 Tracing/Observability。
- 不替换现有的 `agent/trace` 模块（`AgentStep` / `StepCollector` / `ToolExecutionAspect`）——该模块记录业务层步骤时间线，保持不动。
- 不替换 Prometheus/Grafana 指标——它们继续提供 RED 指标；Langfuse 负责 LLM 专项 Trace。

## 3. 已锁定决策

| # | 决策项 | 选择 |
|---|--------|------|
| 1 | 集成路径 | **OTel**：Spring AI Observation → Micrometer → OTLP exporter → Langfuse OTLP 端点 |
| 2 | Langfuse 托管方式 | **docker-compose 自托管 v3**，与业务 pg/redis 完全隔离 |
| 3 | 数据粒度 | **完整 prompt + completion + tool I/O**（`spring.ai.chat.observations.log-prompt/completion=true`，`spring.ai.tools.observations.include-content=true`）；开发环境 100% 采样 |
| 4 | sessionId 关联 | **已启用** — `ObservationFilter` 读取 `AiInteractionContext.getSessionId()` 并发射 OTel 属性 `session.id` |
| 5 | 首次启动体验 | **自动初始化** — 通过 `LANGFUSE_INIT_*` 环境变量（首次启动时自动创建 org/project/user/key） |

## 4. Architecture

```
dawn-ai (Spring Boot 3.2 + Spring AI 1.1)
   │
   │  Spring AI Observation (built-in instrumentation:
   │     ChatModel, EmbeddingModel, VectorStore, Advisor, ToolCalling)
   ▼
Micrometer ObservationRegistry
   │  + LangfuseSessionObservationFilter
   │       ↳ reads AiInteractionContext.getSessionId()
   │       ↳ injects KeyValues: session.id, langfuse.environment
   ▼
micrometer-tracing-bridge-otel  (Span ↔ Observation bridge)
   │
   ▼
OpenTelemetry SDK + OTLP/HTTP Exporter
   │
   │  POST  http://langfuse-web:3000/api/public/otel/v1/traces
   │  Header  Authorization: Basic base64(public_key:secret_key)
   ▼
┌──────────────────────────── Langfuse v3 stack ────────────────────────────┐
│  langfuse-web      (Next.js, ingestion + UI, host:3001 → container:3000)  │
│  langfuse-worker   (background processor)                                 │
│  langfuse-postgres (metadata, host:5433 → container:5432)                 │
│  clickhouse        (trace columnar store, host:8123/9000)                 │
│  langfuse-redis    (queues, host:6380 → container:6379)                   │
│  minio             (S3-compatible object store, host:9100/9101)           │
└────────────────────────────────────────────────────────────────────────────┘
```

## 5. 组件清单与端口分配

| 服务 | 镜像（锁定标签） | 宿主机端口 | 容器内端口 | 备注 |
|-----|-----------------|-----------|-----------|------|
| langfuse-web | `langfuse/langfuse:3` | **3001** | 3000 | Web UI + OTLP 摄入端点。Grafana 已占用 3000。 |
| langfuse-worker | `langfuse/langfuse-worker:3` | – | – | 仅内部访问 |
| langfuse-postgres | `postgres:16-alpine` | 5433 | 5432 | 与业务 `postgres`（5432）隔离 |
| clickhouse | `clickhouse/clickhouse-server:24.3` | – | 8123 / 9000 | **仅内部访问** — 仅供 langfuse-web/worker 消费，不暴露到宿主机以减少开发端口占用 |
| langfuse-redis | `redis:7-alpine` | – | 6379 | **仅内部访问** — 仅供 Langfuse 服务栈使用 |
| minio | `minio/minio:latest` | – | 9000 / 9001 | **仅内部访问** — 仅供 Langfuse 服务栈使用 |

所有服务加入现有的 `dawn-network` 桥接网络。新增命名 Volume：`langfuse_postgres_data`、`clickhouse_data`、`langfuse_redis_data`、`minio_data`。

## 6. 配置项说明

### 6.1 新增环境变量（`.env.example`）

```dotenv
# --- Langfuse first-run bootstrap (langfuse-web container) ---
LANGFUSE_INIT_ORG_ID=dawn-ai
LANGFUSE_INIT_PROJECT_ID=dawn-ai
LANGFUSE_INIT_PROJECT_PUBLIC_KEY=pk-lf-dawn-dev
LANGFUSE_INIT_PROJECT_SECRET_KEY=sk-lf-dawn-dev
LANGFUSE_INIT_USER_EMAIL=admin@dawn.local
LANGFUSE_INIT_USER_PASSWORD=dawn-admin-123
LANGFUSE_INIT_USER_NAME=Dawn Admin

# --- dawn-ai → Langfuse OTLP exporter ---
LANGFUSE_OTLP_ENDPOINT=http://langfuse-web:3000/api/public/otel/v1/traces
LANGFUSE_AUTH_BASE64=<base64(LANGFUSE_INIT_PROJECT_PUBLIC_KEY:LANGFUSE_INIT_PROJECT_SECRET_KEY)>
```

> base64 值由辅助脚本（`scripts/langfuse-auth-header.sh`）一次性生成后粘贴到 `.env` 中；详见 README。

### 6.2 `application.yml` 新增配置

```yaml
management:
  tracing:
    sampling:
      probability: 1.0   # dev: full sampling
  otlp:
    tracing:
      endpoint: ${LANGFUSE_OTLP_ENDPOINT:http://localhost:3001/api/public/otel/v1/traces}
      compression: gzip
      headers:
        Authorization: Basic ${LANGFUSE_AUTH_BASE64:}

spring:
  ai:
    chat:
      observations:
        log-prompt: true
        log-completion: true
    tools:
      observations:
        include-content: true
```

### 6.3 新增 Maven 依赖（`pom.xml`）

- `io.micrometer:micrometer-tracing-bridge-otel`
- `io.opentelemetry:opentelemetry-exporter-otlp`

（`spring-boot-starter-parent` 3.2.5 已通过 `actuator` starter 提供 `management.otlp.tracing.*` 自动配置，无需引入 `opentelemetry-spring-boot-starter`。）

## 7. 代码变更

### 7.1 `LangfuseObservationConfig`（新建）

一个 `@Configuration` 类，包含两个 Bean：

**（a）每 Span 会话过滤器** — 将 `AiInteractionContext` 中的 `session.id` 注入到每个 Observation：

```java
@Bean
ObservationFilter langfuseSessionFilter() {
    return ctx -> {
        String sid = AiInteractionContext.getSessionId();
        if (sid != null && !sid.isBlank()) {
            ctx.addLowCardinalityKeyValue(KeyValue.of("session.id", sid));
        }
        return ctx;
    };
}
```

**（b）静态 Resource 属性** — `langfuse.environment` 是进程级全局配置（非每 Span），通过 SDK 定制器设置为 OTel **Resource Attribute**，避免污染每个 Span payload：

```java
@Bean
OpenTelemetryConfigurer langfuseResourceCustomizer(
        @Value("${langfuse.environment:dev}") String env) {
    return otel -> otel.addResourceCustomizer((res, cfg) ->
        res.merge(Resource.create(Attributes.of(
            AttributeKey.stringKey("langfuse.environment"), env))));
}
```

桥接层将低基数 KeyValue 转换为 OTel Span Attribute；Langfuse 原生识别 `session.id`（官方文档 OTel 属性），用于驱动 **Sessions** 视图。

### 7.2 不修改现有类

- `AiInteractionContext` 已暴露 `getSessionId()` — 直接复用。
- `AiInteractionContextAccessor` 已注册用于 Reactor / executor 传播 — 覆盖运行在 `boundedElastic` 上的 Tool 回调。
- `agent/trace/*` 保持不变。
- Controllers / Services 保持不变。

## 8. 数据流验证（验收标准）

1. 执行 `cp .env.example .env`，再执行 `./scripts/langfuse-auth-header.sh` 填充 `LANGFUSE_AUTH_BASE64`。
2. `docker compose up -d` — 所有容器达到 `healthy` 状态。
3. 访问 `http://localhost:3001`，以 `admin@dawn.local` / `dawn-admin-123` 登录，确认 `dawn-ai` 项目已预创建。
4. 启动应用：`mvn spring-boot:run`（或 `docker compose up app`）。
5. 发送测试请求：`curl -X POST localhost:8080/api/v1/chat -H 'Content-Type: application/json' -d '{"message":"hi","sessionId":"smoke-001"}'`
6. 在 Langfuse UI → **Tracing** 中：5 秒内出现新 Trace，包含：
   - 根 Span `chat`（模型名、延迟、Token 数、完整 prompt + completion）
   - advisor、vector-store query、embedding、tool call 等子 Span（含完整 I/O）
   - 属性 `session.id = smoke-001`
7. **Sessions** 视图将所有 `sessionId = smoke-001` 的 Trace 聚合在一起。

## 9. 故障模式与应对方案

| 故障 | 行为 | 应对措施 |
|------|------|----------|
| Langfuse 服务栈宕机 | OTLP exporter 指数退避重试，最终丢弃 Span。业务请求**仍然成功**。 | OTel SDK 默认行为；显式设置 `otel.exporter.otlp.timeout=10s`。 |
| `LANGFUSE_AUTH_BASE64` 错误 | 摄入端点返回 401，Span 被丢弃。 | OTel 内部日志每分钟记录一次 WARN。 |
| 首次启动初始化竞争 | langfuse-web 在 pg/clickhouse 就绪后可能需要 30–60 秒。 | 应用**不声明** Langfuse 服务栈的 `depends_on`——可观测性故障绝不能阻塞业务启动。langfuse-web 未就绪前的早期 Trace 由 OTel exporter 静默丢弃。 |
| ClickHouse / MinIO 磁盘满 | langfuse-worker 停止持久化 | 开发环境超出范围；已在 README 中说明。 |

## 10. 文档变更

- `README.md` — 追加新章节 **"📊 可观测性（Langfuse）"**，内容包含：如何启动、默认凭据、在哪里查看 Trace、如何更换密鑰。
- `docs/` — 添加本设计规格文档（即当前文件）。
- `.env.example` — 添加第 6.1 节列出的环境变量。
- `scripts/langfuse-auth-header.sh` — 小型辅助脚本，输出 `base64(public:secret)` 以供操作者粘贴到 `.env`。

## 11. 超出范围 / 后续事项

- 生产密钒管理（Vault / AWS Secrets Manager）。
- 生产环境采样率调优（`probability=0.1` + 尾部采样）。
- Langfuse RBAC / 多租户设置。
- 将 Langfuse Evaluation & Datasets 接入 RAG 评估工作流（`rag/evaluation`）——自然后续项，单独立项。
- 用纯粹 OTel Span 替换 `agent/trace` ——长期统一化可行，不在本 PR 范围内。

## 12. 风险

- **容器占用：** 服务栈新增 ~1.5 GB RAM + ~6 个容器；Apple Silicon 开发机应可承载，已在文档中说明注意事项。
- **Spring AI Observation API 漂移：** 1.1 是首个稳定版本系列；如果属性名在小版本中更改，配置需重新检查。锁定 `pom.xml` 中的版本可降低风险。
- **Langfuse OTel 端点稳定性：** `/api/public/otel/v1/traces` 在 Langfuse v3（>= v3.0）中标记为稳定。我们锁定 `langfuse:3` 标签。

## 13. 上线流程

1. 审查通过后将 `feat/langfuse-integration` 合并到 `master`。
2. 无 DB 迁移，对现有客户端无破坏性变更。
3. 现有用户执行 `docker compose up` 将自动获得新服务栈；如需退出，可使用 `docker compose up app postgres redis`（显式指定服务列表）。

---

## 附录 A — 为何 Langfuse 无需官方 Java SDK 就能集成

dawn-ai 不导入任何 Langfuse 客户端库。集成方式是**协议层，而非库层**：

```
[1] Spring AI 1.1 通过 Micrometer Observation API 自动埋点
    ChatModel / Embedding / VectorStore / Advisor / Tool calls（内置，无需额外代码）。

[2] micrometer-tracing-bridge-otel  将 Observation 转换为 OpenTelemetry Span。

[3] opentelemetry-exporter-otlp     将 Span 序列化为 OTLP
    （HTTP + Protobuf，开放行业标准协议）并 POST 到
    http://langfuse-web:3000/api/public/otel/v1/traces
    Header: Authorization: Basic base64(public_key:secret_key)

[4] Langfuse v3 服务端实现了兼容 OTLP 的摄入端点，
    将传入的 OTel Span 解码为内部的
    Trace / Observation / Generation 模型，持久化到 ClickHouse。
```

**为何不需要 SDK**：Langfuse 将自身暴露为标准 OTLP Trace 后端。任何具备 OpenTelemetry exporter 的语言都能与其通信。类比：MySQL 没有官方 Rust 驱动，但 Rust 程序可以连接 MySQL，因为 MySQL 线路协议是公开的——客户端只需能说该协议即可。

**注意事项**：Span 属性名必须遵循 Langfuse 官方文档的 OTel 语义约定（`session.id`、`gen_ai.usage.input_tokens`、`gen_ai.prompt` 等），UI 才能正确解析。这就是为什么第 7.1 节要通过 `ObservationFilter` 显式发射 `session.id`——Spring AI 默认不发射它。

> Langfuse 还提供一个**私有**摄入 API（`/api/public/ingestion`，自定义 JSON），该 API *确实*需要 SDK 才能消费。我们特意避开它；OTLP 能提供同等覆盖度，且零专有依赖。

---

## 附录 B — OTLP / Langfuse vs. Prometheus + Grafana

两个服务栈分别解决**可观测性三大支柱中的不同部分**，并列**而不是互相替代**。

### B.1 三大支柱

| 支柱 | 数据形态 | 回答的问题 | 归属 |
|------|---------|------------|------|
| **指标（Metrics）** | 时序数字（counter / histogram） | “整体多快多稳？P99？错误率？” | **Prometheus + Grafana**（现有） |
| **链路跟踪（Traces）** | 因果关联的 Span 树 + 上下文 | “**这一个**请求为什么慢/错/贵？用了什么 Prompt？哪个 Tool？” | **OTLP → Langfuse**（新增） |
| 日志（Logs） | 结构化文本 | “故障时刻具体发生了什么？” | logback（暂未集中化） |

### B.2 集成后的拓扑结构

```
                           dawn-ai (Spring Boot)
                                   │
                  ┌────────────────┴────────────────┐
                  │ Micrometer (unified observation) │
                  │  - MeterRegistry      (metrics)  │
                  │  - ObservationRegistry (traces)  │
                  └──────┬──────────────────┬────────┘
                         │                  │
              ┌──────────▼─────┐   ┌────────▼──────────────┐
              │ Prometheus     │   │ tracing-bridge-otel   │
              │ Registry       │   │   ↓                   │
              │ (/actuator/    │   │ OTel SDK              │
              │  prometheus)   │   │   ↓ OTLP/HTTP (push)  │
              └────────┬───────┘   │                       │
                       │ pull       └──────────┬────────────┘
                       ▼                       ▼
              ┌────────────────┐      ┌────────────────────┐
              │ Prometheus TSDB│      │ Langfuse v3        │
              └────────┬───────┘      │ (OTLP ingest +     │
                       ▼              │  ClickHouse) + UI  │
              ┌────────────────┐      └────────────────────┘
              │ Grafana        │
              └────────────────┘
                       ↑                       ↑
              "趋势、SLO、汇总指标"        "单会话深入分析：
                                          Prompt、Tool 调用、
                                          慢或贵的原因"
```

### B.3 OTLP vs. Prometheus 协议对比

| 维度 | Prometheus | OTLP |
|------|------------|------|
| 标准权属 | Prometheus 项目（CNCF） | OpenTelemetry（CNCF） |
| 方向 | 服务端 **拉取** 应用的 `/metrics` | 应用**推送**到 collector / backend |
| 传输指标？ | 是（唯一用途） | 是——但我们不使用此路 |
| 传输链路？ | 否 | **是**——我们使用的部分 |
| 传输日志？ | 否 | 是（可选） |

OTLP 理论上也能替代 Prometheus 的指标职能（通过 OTel Collector → `prometheus_remote_write`），但我们**特意不这样做**：现有指标流水线运转良好；重构带不来价值且存在回归风险。本次集成仅涉及 **Trace 路**。

### B.4 同一埋点，两个输出

一段 Micrometer 埋点代码——例如 Spring AI 的 `ChatModel` Observation——同时向**两个汇流池**输出：

- `MeterRegistry` 侧发射 histogram → Prometheus → Grafana（“LLM 调用延迟 P99”）；
- `ObservationRegistry` 侧发射 Span → OTLP → Langfuse（“这次慢请求的具体 Prompt 和 Completion 是什么”）。

零重复埋点成本。

### B.5 运维分工示例

> 用户反映：“今天 Bot 很慢。”
>
> 1. 打开 **Grafana** → 发现 14:30–14:45 间 P99 有尖刺。
> 2. 同一看板显示 Token 率正常，但 Tool 调用延迟翻倍。
> 3. 切换到 **Langfuse** → 过滤该时间窗的 Trace，按时长排序 → 打开一个 Trace → 发现问题 Tool Span 耗时 8 秒，检查其完整 Input/Output 定位根本原因（例如上游 API 限流）。

Grafana 无法展示单次 Prompt 内容；Langfuse 无法展示车队级趋势。各占一半可观测性图景。

### B.6 一句话类比

- **Prometheus + Grafana** = 年度体检报告（趋势、预警）。
- **Langfuse** = 病历档案（每次问诊的完整记录，可回放）。

两者缺一不可，互不替代。
