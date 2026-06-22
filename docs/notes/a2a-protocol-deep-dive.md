# Agent2Agent (A2A) 协议深度解析

> **文档性质**：团队内部知识共享文档
> **撰写日期**：2026-06-22
> **协议版本**：A2A v1.0.0 Stable (2026 年 4 月)

---

## 一、协议概述与背景

### 1.1 A2A 是什么

A2A (Agent-to-Agent Protocol) 是由 Google 于 2025 年 4 月发起的开放标准协议，专为 **AI Agent 之间的发现、通信与任务协作** 而设计。协议于 2025 年 6 月捐赠给 **Linux Foundation**，归入 Agentic AI Foundation 治理，2026 年 4 月发布 v1.0 稳定版，已获得超过 **150 个组织**的生产级采用，GitHub 获得 22K+ stars。

**核心定位**：Agent 之间的协作与任务委派层（水平集成）。

A2A 解决的是 **"多 Agent 协作问题"**：让不同框架（LangGraph、CrewAI、ADK 等）、不同厂商构建的 Agent 能够相互发现、通信、委派任务，形成真正的多 Agent 协作网络。

### 1.2 为什么需要 A2A

在 AI Agent 生态快速发展的背景下，单个 Agent 的能力边界日益清晰。复杂的业务场景（如企业级出差规划、跨域数据分析、多步骤审批流程）需要 **多个专业 Agent 协同完成**。然而，不同框架、不同团队构建的 Agent 缺乏统一的通信标准，导致：

- Agent 间无法自动发现彼此的能力
- 任务委派缺乏标准化生命周期管理
- 异步长时间任务缺乏状态跟踪机制
- 跨厂商互操作需要大量定制化适配

A2A 正是为解决这些问题而生。

### 1.3 关键时间线

| 时间 | 事件 |
|------|------|
| 2024 年 11 月 | Anthropic 发布 MCP (Model Context Protocol) |
| 2025 年 3 月 | IBM 发布 ACP (Agent Communication Protocol)，捐赠给 Linux Foundation |
| 2025 年 4 月 | **Google 发布 A2A**，50+ 合作伙伴 |
| 2025 年 6 月 | A2A 捐赠给 Linux Foundation，归入 Agentic AI Foundation |
| 2025 年 8 月 | ACP 正式合并入 A2A（IBM + Google 联合公告） |
| 2025 年 12 月 | MCP 捐赠给 Linux Foundation，同样归入 Agentic AI Foundation |
| 2026 年初 | A2A v0.3 发布（gRPC 支持、Signed Agent Cards） |
| 2026 年 4 月 | **A2A v1.0 稳定版发布**，150+ 生产组织采用 |

### 1.4 治理与战略意义

A2A 和 MCP 现在都在 Linux Foundation 旗下的 **Agentic AI Foundation** 中治理。这意味着：

- **厂商中立**：OpenAI、Google、Microsoft、Anthropic、AWS 都是参与者，没有单一厂商控制
- **开放 RFC 流程**：Spec 变更通过公开 RFC 提案，社区评审
- **长期稳定性**：Linux Foundation 的背书降低了企业采用的风险

竞争对手们（Google、Anthropic、OpenAI、Microsoft）同意共同的基础设施标准，类似于早年 HTTP、TCP/IP 的标准化过程，标志着 AI Agent 通信正在从"百花齐放"走向"基础设施共识"。

---

## 二、核心规范详解

### 2.1 Agent Card -- Agent 的"数字名片"

Agent Card 是 A2A 协议的核心发现机制。每个 Agent 通过 JSON 格式的 Agent Card 对外发布自身的能力、技能、通信接口和认证要求。

**托管路径**：`GET /.well-known/agent-card.json`

**完整 JSON 结构**：

```json
{
  "name": "Weather Agent",
  "description": "Provides real-time weather information for cities worldwide",
  "version": "1.0.0",
  "protocolVersion": "1.0.0",
  "url": "http://localhost:8080",
  "supportedInterfaces": [
    {
      "protocol": "jsonrpc",
      "url": "http://localhost:8080"
    },
    {
      "protocol": "grpc",
      "url": "http://localhost:8081"
    }
  ],
  "capabilities": {
    "streaming": true,
    "pushNotifications": false
  },
  "authentication": {
    "schemes": ["bearer"],
    "credentials": "OAuth2 token required"
  },
  "defaultInputModes": ["text"],
  "defaultOutputModes": ["text"],
  "skills": [
    {
      "id": "weather_search",
      "name": "Search Weather",
      "description": "Get current temperature and conditions for any city",
      "tags": ["weather", "temperature", "forecast"],
      "examples": [
        "What's the weather in Beijing?",
        "Temperature in London today"
      ]
    },
    {
      "id": "weather_forecast",
      "name": "Weather Forecast",
      "description": "Get 7-day weather forecast",
      "tags": ["weather", "forecast"],
      "examples": [
        "7-day forecast for Tokyo"
      ]
    }
  ]
}
```

**关键字段说明**：

| 字段 | 说明 |
|------|------|
| `name` / `description` | Agent 的名称与能力描述 |
| `protocolVersion` | 遵循的 A2A 协议版本 |
| `supportedInterfaces` | 支持的 transport 协议与对应 URL |
| `capabilities` | 声明是否支持 streaming、push notification 等 |
| `authentication` | 认证方案声明（OAuth2、API Key 等） |
| `skills` | Agent 拥有的技能列表，支持基于标签的能力发现 |

**v1.0 新增**：**Signed Agent Cards** -- 支持加密签名，防止 Agent Card 被篡改。

### 2.2 Task -- 任务生命周期管理

Task 是 A2A 中的核心工作单元，代表 Client Agent 委派给 Server Agent 的一个任务。每个 Task 有唯一 ID，并且具有完整的状态机管理。

**Task JSON 结构**：

```json
{
  "id": "task-a1b2c3d4",
  "sessionId": "session-001",
  "status": {
    "state": "working",
    "message": {
      "role": "agent",
      "parts": [
        {
          "type": "text",
          "text": "正在查询北京天气数据..."
        }
      ]
    },
    "timestamp": "2026-06-22T10:30:00Z"
  },
  "artifacts": [],
  "history": []
}
```

### 2.3 Task 状态机

Task 在其生命周期内经历以下状态转换：

```
                   +-----------+
                   | submitted |  (初始状态：任务已提交)
                   +-----+-----+
                         |
                         v
                   +-----------+
              +--->|  working  |  (Agent 正在处理)
              |    +-----+-----+
              |          |
              |    +-----+-----+-----+-----+
              |    |           |           |
              |    v           v           v
         +----+------+  +-----------+  +---------+
         |  input-   |  | completed |  | failed  |
         |  required |  +-----------+  +---------+
         +-----------+   (成功完成)    (处理失败)
         (需要用户补充               
          额外输入)                    +-----------+
                                      | canceled  |
                                      +-----------+
                                      (被取消)
```

**状态说明**：

| 状态 | 含义 |
|------|------|
| `submitted` | 任务已提交，等待 Agent 开始处理 |
| `working` | Agent 正在处理任务 |
| `input-required` | Agent 需要 Client 提供额外输入才能继续 |
| `completed` | 任务成功完成 |
| `failed` | 任务处理失败 |
| `canceled` | 任务被取消 |

**关键设计**：`input-required` 状态是 A2A 的亮点设计，支持 Agent 之间的 **多轮协商对话**。例如旅行规划 Agent 可能需要询问用户预算偏好后才能继续。

### 2.4 Message -- 消息交换

Message 是 Agent 间通信的基本单元，包含角色标识和多个 Part 组成的内容。

```json
{
  "role": "user",
  "parts": [
    {
      "type": "text",
      "text": "帮我查一下北京今天的天气"
    }
  ]
}
```

`role` 取值：
- `user` -- 来自 Client Agent 或最终用户的消息
- `agent` -- 来自 Server Agent 的响应消息

### 2.5 Part -- 内容片段

Part 是 Message 和 Artifact 的内容载体，支持多模态数据传输。

**TextPart**：

```json
{
  "type": "text",
  "text": "北京今天天气晴朗，气温 25°C"
}
```

**FilePart**（文件/二进制数据）：

```json
{
  "type": "file",
  "file": {
    "name": "weather-chart.png",
    "mimeType": "image/png",
    "bytes": "base64-encoded-data..."
  }
}
```

**DataPart**（结构化数据）：

```json
{
  "type": "data",
  "data": {
    "city": "Beijing",
    "temperature": 25,
    "unit": "celsius",
    "condition": "sunny",
    "humidity": 45
  }
}
```

### 2.6 Artifact -- 任务输出结果

Artifact 是 Agent 完成任务后产生的结构化结果，可以包含多个 Part。它与普通 Message 的区别在于：Artifact 是 Task 的 **最终交付物**，而 Message 是过程中的通信。

```json
{
  "name": "weather-report",
  "description": "北京天气查询结果",
  "parts": [
    {
      "type": "text",
      "text": "北京 2026-06-22 天气报告：晴，25°C，湿度 45%，东南风 3 级"
    },
    {
      "type": "data",
      "data": {
        "city": "Beijing",
        "date": "2026-06-22",
        "temperature": 25,
        "humidity": 45,
        "wind": "SE 3"
      }
    }
  ],
  "index": 0,
  "append": false,
  "lastChunk": true
}
```

---

## 三、通信机制

A2A 协议支持三种通信模式，适应不同的业务场景。

### 3.1 同步请求-响应 (JSON-RPC)

最基础的通信模式，基于 JSON-RPC 2.0。Client 发送请求，Server 返回完整结果。

**请求**：

```json
{
  "jsonrpc": "2.0",
  "id": "req-001",
  "method": "message/send",
  "params": {
    "message": {
      "role": "user",
      "parts": [
        {
          "type": "text",
          "text": "What's the weather in Beijing?"
        }
      ]
    }
  }
}
```

**响应**：

```json
{
  "jsonrpc": "2.0",
  "id": "req-001",
  "result": {
    "id": "task-001",
    "sessionId": "session-001",
    "status": {
      "state": "completed"
    },
    "artifacts": [
      {
        "parts": [
          {
            "type": "text",
            "text": "Beijing: 25°C, Sunny, Humidity 45%"
          }
        ]
      }
    ]
  }
}
```

**适用场景**：快速响应的简单查询，任务处理时间短（通常秒级）。

### 3.2 SSE Streaming（服务端推送事件流）

用于需要实时进度反馈的场景。Client 通过 `message/stream` 方法发起请求，Server 通过 Server-Sent Events (SSE) 持续推送任务状态更新和中间结果。

**请求**（与同步类似，但 method 为 `message/stream`，Accept Header 声明 SSE）：

```
POST / HTTP/1.1
Content-Type: application/json
Accept: text/event-stream

{
  "jsonrpc": "2.0",
  "id": "req-002",
  "method": "message/stream",
  "params": {
    "message": {
      "role": "user",
      "parts": [{"type": "text", "text": "Analyze Q2 revenue trends"}]
    }
  }
}
```

**SSE 事件流**：

```
event: task.status
data: {"taskId": "task-002", "status": {"state": "submitted"}}

event: task.status
data: {"taskId": "task-002", "status": {"state": "working"}}

event: task.artifact
data: {"taskId": "task-002", "artifact": {"parts": [{"type": "text", "text": "Q2 revenue increased 15% YoY..."}]}}

event: task.status
data: {"taskId": "task-002", "status": {"state": "completed"}}
```

**适用场景**：复杂分析任务、需要显示处理进度的 UI 场景、长时间运行的任务。

### 3.3 Push Notification（推送通知）

用于真正的长时间异步任务。Client 注册 Webhook 回调地址，Server 在任务状态变更时主动推送通知。

**工作流程**：

1. Client 发送任务请求
2. Server 返回 `taskId` 和 `submitted` 状态
3. Client 注册 Push Notification Webhook：

```json
{
  "jsonrpc": "2.0",
  "id": "req-003",
  "method": "tasks/pushNotification/set",
  "params": {
    "taskId": "task-003",
    "pushNotificationConfig": {
      "url": "https://client.example.com/a2a/webhook",
      "authentication": {
        "schemes": ["bearer"],
        "credentials": "webhook-secret-token"
      }
    }
  }
}
```

4. Server 在状态变更时 POST 到 Webhook URL

**适用场景**：跨小时/跨天的长时间任务、Client 不能保持长连接的场景。

### 3.4 v1.0 Multi-Protocol 支持

A2A v1.0 新增了三种 protocol binding：

| Protocol | 特点 | 适用场景 |
|----------|------|----------|
| **JSON-RPC 2.0** | 标准、通用 | 默认选择，广泛兼容 |
| **gRPC** | 高性能、强类型 | 高吞吐、低延迟的内部服务通信 |
| **HTTP+JSON/REST** | 简单、无 SDK 依赖 | 快速集成、cURL 友好 |

---

## 四、安全与认证

### 4.1 Agent Card 认证声明

Agent Card 的 `authentication` 字段声明 Server Agent 支持的认证方案：

```json
{
  "authentication": {
    "schemes": ["bearer", "oauth2"],
    "credentials": "Contact admin@example.com for API keys"
  }
}
```

### 4.2 安全最佳实践

**传输层安全**：
- 强制使用 **HTTPS/TLS** 加密所有 A2A 通信
- 采用 **mTLS**（双向 TLS）保护高安全要求的 Agent 间通信
- 网络层限制（VPC、防火墙规则）控制可访问 Agent 范围

**认证与授权**：
- 支持 **OAuth 2.0 / JWT** 标准认证流程
- Agent Card 本身可加 access control 和认证保护（可能包含敏感的内部 URL、技能描述）
- v1.0 **Signed Agent Cards**：加密签名验证，防止 Agent Card 被篡改

**运维安全**：
- 强制执行 **per-client 和 per-IP 连接限制**
- 实施 **rate limiting 和 session timeout** 防御 DoS 和暴力攻击
- **异常检测告警**：标记重复认证失败或异常任务模式

### 4.3 已知安全缺口

- **无 Authorization downscoping 机制**：高权限 Orchestrator 委派给子 Agent 时，协议不定义如何缩小 token 权限范围。生产中需借助 RFC 8693 Token Exchange 实现。
- **跨 Agent 链的安全边界**：分布式授权链缺乏集中策略执行点，需要 Agent Gateway 层统一管控。

---

## 五、A2A vs MCP vs ACP 对比

### 5.1 三者定位

Google 官方用了一个 **汽车修理店** 的类比说明 A2A 与 MCP 的关系：

- **A2A** 是你（用户/其他 Agent）与修理店技师（Agent）之间的对话协议："我的车有异响" -> "发张左轮照片给我" -> "我发现有液体泄漏"
- **MCP** 是技师使用工具的协议：使用诊断扫描仪（tool call）、查阅维修手册数据库（resource read）、升起举升机检查底盘（tool call）

**一句话总结：A2A 管 Agent 间怎么"说话"，MCP 管 Agent 怎么"干活"。**

### 5.2 全面对比

| 维度 | MCP | A2A | ACP (已合并入 A2A) |
|------|-----|-----|-----|
| **发起方** | Anthropic (2024.11) | Google (2025.04) | IBM (2025.03) |
| **核心用途** | Agent -> 工具/数据 | Agent -> Agent | Agent -> Agent |
| **架构模型** | Client-Server | Peer-to-Peer 对等 | RESTful HTTP |
| **通信协议** | JSON-RPC 2.0 | JSON-RPC + SSE + gRPC | RESTful HTTP |
| **SDK 依赖** | 需要 SDK | 需要 SDK | 无需 SDK（cURL 即可） |
| **流式支持** | 基础 | Task streaming + SSE | Async-first，HTTP + SSE |
| **发现机制** | 依赖 Host 配置 | Agent Card（在线发现） | 离线发现（元数据嵌入分发包） |
| **状态管理** | 无状态 | 有状态（Task 状态机） | 有状态 + 无状态均支持 |
| **多模态** | 有限 | 原生支持（TextPart / FilePart / DataPart） | 原生 MIME type 支持 |
| **Memory** | Server 内部有限内存 | 无内置 | 内置 Memory 概念 |
| **类比** | USB-C 接口 | 公司协作平台 | -- |
| **当前状态** | LF 治理，活跃 | LF 治理，v1.0 稳定版 | 已合并入 A2A |

### 5.3 ACP 合并入 A2A

2025 年 8 月 29 日，IBM Research 的 Kate Blair 和 Google 的 Todd Segal 联合宣布：**ACP 正式合并到 A2A 中**，统一在 Linux Foundation 旗下。

合并原因：两个协议在目标上高度一致（都做 Agent-to-Agent），合并加速标准统一。ACP 的优势特性（离线发现、RESTful 简洁性、无 SDK 依赖）已被引入 A2A v1.0。

> "By bringing the assets and expertise behind ACP into A2A, we can build a single, more powerful standard." -- Kate Blair, IBM Research

### 5.4 选型决策矩阵

| 场景 | 推荐协议 | 理由 |
|------|----------|------|
| 单 Agent + 多工具集成 | **MCP** | 标准化工具接入，无需 Agent 间通信 |
| 内部 Copilot（代码/文档助手） | **MCP** | 核心需求是工具连接，不涉及多 Agent |
| 多 Agent 跨域协作 | **A2A** | 需要能力发现、任务委派、异步协作 |
| 跨厂商 Agent 互操作 | **A2A** | A2A 专为跨框架设计 |
| 企业级复杂工作流 | **A2A + MCP** | A2A 编排 Agent，MCP 连接工具 |
| 长时间运行的任务 | **A2A** | Task 状态机支持完整生命周期 |
| Agent 间协商/多轮对话 | **A2A** | `input-required` 状态支持对话式协作 |

### 5.5 互补架构：混合 A2A + MCP

```
用户请求
    |
    v
[Orchestrator Agent]
    |
    |--- A2A ---> [Flight Agent]  --- MCP ---> 航班搜索 API
    |                                --- MCP ---> 价格比较数据库
    |
    |--- A2A ---> [Calendar Agent] --- MCP ---> Google Calendar API
    |
    |--- A2A ---> [Payment Agent]  --- MCP ---> 支付网关
    |
    v
汇总结果返回用户
```

**工作流程**：

1. **用户提交复杂请求**（如"帮我订下周去伦敦的出差行程"）
2. **Orchestrator 通过 A2A 发现并委派子任务**：通过 Agent Card 发现具备航班搜索能力的 Flight Agent，通过 Task 机制委派任务
3. **子 Agent 内部通过 MCP 调用工具**：Flight Agent 使用 MCP 连接航班搜索 API、价格数据库
4. **结果通过 A2A 回传**：子 Agent 将结果封装为 Artifact，通过 A2A 回传给 Orchestrator
5. **Orchestrator 汇总并响应用户**

---

## 六、架构模式与最佳实践

### 6.1 网络拓扑

#### Direct（点对点）
- 每个 Agent 直接与目标 Agent 建立 HTTP(S) 连接
- 优点：延迟低、实现简单，适合 2-5 个 Agent 的小规模场景
- 致命缺陷：连接数呈 **O(n^2) 二次增长**。5 个 Agent 需 10 条连接，50 个 Agent 需超 1,200 条连接

#### Hub-and-Spoke（星型）
- 一个中心 Orchestrator Agent 充当 Hub，所有 Spoke Agent 只连 Hub
- n 个 Agent 仅需 n-1 条连接，复杂度 O(n)
- 缺点：Hub 成为 **单点故障 (SPOF)**

#### Mesh（网格）
- Agent 通过 **分布式 Registry** 发布能力、发现伙伴，形成去中心化网络
- 优点：消除 SPOF；缺点：需要额外的 Discovery 基础设施

#### 生产建议：混合拓扑
大多数企业生产系统采用 **混合拓扑**：顶层用 Hub-and-Spoke 做编排控制，子域内用 Mesh 做 peer-to-peer 协作。

```
                          +-----------+
                          |   用户    |
                          +-----+-----+
                                |
                                v
                    +---------------------+
                    | Orchestrator Agent  |
                    | (主编排 Agent)       |
                    +---+-------+-------+-+
                        |       |       |
                   A2A  |  A2A  |  A2A  |
                        v       v       v
              +--------+ +--------+ +--------+
              |Analytics| |  HR    | |Finance |
              | Agent   | | Agent  | | Agent  |
              +---+---+-+ +---+----+ +---+----+
                  |   |       |           |
             MCP  |   | MCP   | MCP       | MCP
                  v   v       v           v
              +----+ +----+ +------+ +--------+
              | BI | |Data| |HRIS  | |ERP     |
              |Tool| |Lake| |System| |System  |
              +----+ +----+ +------+ +--------+
```

### 6.2 Agent 注册与发现

A2A 的发现机制分三层策略：

**策略一：Well-Known URI（静态发现）**
- 每个 Agent 在 `/.well-known/agent.json` 托管 Agent Card
- 适用场景：公开 Agent、组织内大范围发现
- 局限：需要预先知道 Agent 的 base URL

**策略二：Curated Registry（注册中心）**
- 中央 Registry 服务维护 Agent Card 集合，Client 按 skill / tag / provider 查询
- 已有实现：**Nacos 3.1.0** 原生支持 A2A Agent 注册发现，AgentScope Runtime 支持 Nacos 集成
- 注意：**A2A 规范本身不规定 Registry 的标准 API**，这是留给实现者的空白

**策略三：Direct Configuration（私有配置）**
- 通过硬编码 URL、配置文件、环境变量直接指定
- 适用场景：开发调试阶段

### 6.3 负载均衡与扩展

A2A 基于 HTTP(S)，天然兼容现有 Web 基础设施的 scaling 模式：

- **容器化部署**：Cloud Run / Kubernetes，利用 serverless 自动扩缩容
- **Agent Gateway 模式**：Solo.io Agent Gateway（已捐赠给 Linux Foundation）、TrueFoundry Agent Gateway -- 提供 A2A/MCP 协议感知的流量管理
- **标准 Load Balancer**：L7 负载均衡器，A2A v1.0 的 web-aligned architecture 明确支持
- Agent 设计应 **无状态**（stateless），状态外置到 Redis / 数据库

### 6.4 可观测性

#### Distributed Tracing (W3C Trace Context)
A2A 推荐使用 **OpenTelemetry + W3C Trace Context**：
- 在 HTTP 请求中传播 `traceparent` / `tracestate` header
- **关键要求**：Orchestrator 调用 Worker Agent 时，**必须传播 `traceparent` header**，否则端到端调试几乎不可能
- Cisco Outshift 的 AGNTCY/observe SDK 已实现 A2A 的 OTel instrumentation

#### 日志关联
每个 A2A Task 有唯一 `taskId`，结合 trace ID 和 session ID 实现三维关联。最佳实践：使用 structured JSON logging，字段统一包含 `traceId`、`taskId`、`sessionId`。

#### Metrics
- **核心指标**：token 消耗量、延迟百分位 (p50/p95/p99)、error rate、throughput (RPS)、cost per request
- **Agent 特有指标**：推理步骤数、工具调用次数、delegation 深度、session 持续时间

### 6.5 错误处理与重试

**A2A 规范定义的错误体系**：

| Error Type | JSON-RPC Code | HTTP Status | 含义 |
|---|---|---|---|
| `TaskNotFoundError` | -32001 | 404 | 任务不存在 |
| `TaskNotCancelableError` | -32002 | 400 | 任务无法取消 |
| `PushNotificationNotSupportedError` | -32003 | 400 | 不支持推送通知 |
| `UnsupportedOperationError` | -32004 | 400 | 不支持的操作 |
| `ContentTypeNotSupportedError` | -32005 | 400 | 不支持的内容类型 |
| `InvalidAgentResponseError` | -32006 | 500 | Agent 返回无效响应 |

**重试策略**（协议层面留给 Client 实现）：
- 使用 **exponential backoff + jitter** 避免 thundering herd
- 设置 **delegation-depth limit** 防止递归循环
- 设置 **per-run budget** 控制单次 workflow 的总消耗
- 配置 **timeout 和 stall detection** 防止 Agent 无限等待子任务

**经典故障案例**（来自 TrueFoundry）：一个 sub-agent 遇到瞬态错误后重新调用 orchestrator，orchestrator 再次 delegate，形成循环，运行了数小时，产生了数万次调用。根因：共享 service key 无法区分 Agent、调用间无 graph 记录、无 per-agent rate limit。

### 6.6 已知局限性

**架构层面**：
1. **HTTP 点对点模型**：连接数随 Agent 数量呈 N^2 增长
2. **紧耦合**：每个 Agent 必须知道对端的 endpoint / auth / 可用性状态
3. **无原生 Pub/Sub**：无法 fan-out，无法做 event-driven reactive 通信
4. **无原生 Message Queue**：无持久化消息、无离线投递保证

**协议层面**：
5. **无标准化 per-skill JSON Schema**：Agent 声明接受 JSON，但不规定具体结构
6. **无标准 Registry API**：Agent 注册中心的交互协议未标准化
7. **Schema 版本演进未定义**：Agent 能力变化时无标准的向后兼容机制

HiveMQ 和 Apache Kafka 社区指出了这些问题，并提出用 MQTT / Kafka 作为 A2A 的 event backbone。客观评价：这些批判有其商业动机，但指出的问题客观存在。**生产级方案很可能是混合架构**：A2A 语义 + HTTP/gRPC 用于同步低延迟交互，MQTT/Kafka 用于异步高扩展场景。

---

## 七、应用场景与案例

### 7.1 企业出差规划

**场景**：员工提交"帮我订下周三去伦敦的出差行程"

**Agent 协作流**：
1. Orchestrator Agent 解析意图，分解为子任务
2. 通过 A2A 发现 Flight Agent、Hotel Agent、Calendar Agent
3. Flight Agent（通过 MCP 调用航班 API）搜索航班并返回 Artifact
4. Hotel Agent（通过 MCP 调用酒店平台）推荐住宿
5. Calendar Agent（通过 MCP 调用 Google Calendar）检查日程冲突
6. 如有冲突，Calendar Agent 返回 `input-required` 状态请求用户决策
7. Orchestrator 汇总所有 Artifact，生成完整行程方案

### 7.2 跨部门数据分析

**场景**：CFO 询问"Q2 各业务线的利润贡献变化"

```
CFO 请求 -> Orchestrator
  |
  +--- A2A ---> Analytics Agent (BI 数据) --- MCP ---> Tableau API
  +--- A2A ---> Finance Agent (财务数据)   --- MCP ---> ERP System
  +--- A2A ---> HR Agent (人力成本)        --- MCP ---> HRIS
  |
  v
综合分析报告（含交叉对比）
```

### 7.3 跨框架 Agent 互操作

A2A 的核心价值之一是跨框架互操作。一个团队用 LangGraph 构建的分析 Agent，另一个团队用 CrewAI 构建的报告 Agent，以及第三方提供的 SaaS Agent，都可以通过 A2A Agent Card 相互发现并协作，无需关心对方的实现框架。

---

## 八、代码实战

### 8.1 Python SDK 示例

#### 8.1.1 A2A Server（基于官方 Python SDK）

```python
"""
A2A Weather Agent Server - Python SDK 示例
依赖: pip install a2a-sdk
"""

from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.events import EventQueue
from a2a.server.apps import A2AStarletteApplication
from a2a.types import (
    AgentCard,
    AgentCapabilities,
    AgentSkill,
    TextPart,
    Message,
    Part,
)
import uvicorn


class WeatherAgentExecutor(AgentExecutor):
    """天气查询 Agent 的核心执行器"""

    async def execute(self, context: RequestContext, event_queue: EventQueue):
        # 从消息中提取用户文本
        user_text = self._extract_text(context.message)

        # 发送 "working" 状态
        await event_queue.enqueue_status_update("working")

        # 模拟天气查询（实际项目中调用天气 API）
        weather_info = self._get_weather(user_text)

        # 构建并发送 Artifact
        response_part = TextPart(type="text", text=weather_info)
        await event_queue.enqueue_artifact(parts=[response_part])

        # 标记任务完成
        await event_queue.enqueue_status_update("completed")

    async def cancel(self, context: RequestContext, event_queue: EventQueue):
        await event_queue.enqueue_status_update("canceled")

    def _extract_text(self, message: Message) -> str:
        texts = []
        for part in message.parts:
            if isinstance(part.root, TextPart):
                texts.append(part.root.text)
        return " ".join(texts)

    def _get_weather(self, query: str) -> str:
        # 简化示例；生产中应调用真实天气 API
        return f"查询结果：晴天，25°C，湿度 45%（基于查询: {query}）"


def create_agent_card() -> AgentCard:
    """定义 Agent Card"""
    return AgentCard(
        name="Weather Agent",
        description="Provides real-time weather information",
        version="1.0.0",
        url="http://localhost:9000",
        capabilities=AgentCapabilities(streaming=True, pushNotifications=False),
        defaultInputModes=["text"],
        defaultOutputModes=["text"],
        skills=[
            AgentSkill(
                id="weather_search",
                name="Search Weather",
                description="Get weather for any city worldwide",
                tags=["weather", "temperature"],
                examples=["What's the weather in Beijing?", "Temperature in London"],
            )
        ],
    )


# 创建 A2A 应用
agent_card = create_agent_card()
agent_executor = WeatherAgentExecutor()

app = A2AStarletteApplication(
    agent_card=agent_card,
    agent_executor=agent_executor,
)

if __name__ == "__main__":
    uvicorn.run(app.build(), host="0.0.0.0", port=9000)
```

#### 8.1.2 A2A Client（Python SDK）

```python
"""
A2A Client - 发现 Agent 并发送任务
依赖: pip install a2a-sdk httpx
"""

import asyncio
import httpx
from a2a.client import A2AClient, A2ACardResolver
from a2a.types import (
    MessageSendParams,
    Message,
    TextPart,
    SendMessageRequest,
)


async def main():
    # 1. 发现远程 Agent
    async with httpx.AsyncClient() as httpx_client:
        card_resolver = A2ACardResolver(
            httpx_client=httpx_client,
            base_url="http://localhost:9000",
        )
        agent_card = await card_resolver.get_agent_card()

        print(f"发现 Agent: {agent_card.name}")
        print(f"  描述: {agent_card.description}")
        print(f"  技能: {[s.name for s in agent_card.skills]}")

        # 2. 创建 Client
        client = A2AClient(
            httpx_client=httpx_client,
            agent_card=agent_card,
        )

        # 3. 发送同步消息
        request = SendMessageRequest(
            id="req-001",
            params=MessageSendParams(
                message=Message(
                    role="user",
                    parts=[TextPart(type="text", text="北京今天天气怎么样？")],
                )
            ),
        )

        response = await client.send_message(request)
        print(f"\n任务状态: {response.root.result.status.state}")

        if response.root.result.artifacts:
            for artifact in response.root.result.artifacts:
                for part in artifact.parts:
                    if hasattr(part.root, "text"):
                        print(f"结果: {part.root.text}")

        # 4. SSE Streaming 模式
        print("\n--- Streaming 模式 ---")
        stream_request = SendMessageRequest(
            id="req-002",
            params=MessageSendParams(
                message=Message(
                    role="user",
                    parts=[TextPart(type="text", text="上海 7 天天气预报")],
                )
            ),
        )

        async for event in client.send_message_streaming(stream_request):
            if hasattr(event, "status"):
                print(f"  状态更新: {event.status.state}")
            elif hasattr(event, "artifact"):
                for part in event.artifact.parts:
                    if hasattr(part.root, "text"):
                        print(f"  收到数据: {part.root.text}")


if __name__ == "__main__":
    asyncio.run(main())
```

#### 8.1.3 Multi-Agent Orchestrator（Python 示例）

```python
"""
Multi-Agent Orchestrator - 协调多个 A2A Agent 完成复合任务
"""

import asyncio
import httpx
from a2a.client import A2AClient, A2ACardResolver
from a2a.types import (
    MessageSendParams,
    Message,
    TextPart,
    SendMessageRequest,
)
from dataclasses import dataclass
from typing import Optional


@dataclass
class AgentInfo:
    name: str
    url: str
    client: Optional[A2AClient] = None


class MultiAgentOrchestrator:
    """多 Agent 编排器"""

    def __init__(self, agent_urls: list[str]):
        self.agent_urls = agent_urls
        self.agents: dict[str, AgentInfo] = {}
        self.httpx_client = httpx.AsyncClient(timeout=60.0)

    async def discover_agents(self):
        """发现并注册所有可用 Agent"""
        for url in self.agent_urls:
            try:
                resolver = A2ACardResolver(
                    httpx_client=self.httpx_client,
                    base_url=url,
                )
                card = await resolver.get_agent_card()
                client = A2AClient(
                    httpx_client=self.httpx_client,
                    agent_card=card,
                )
                self.agents[card.name] = AgentInfo(
                    name=card.name,
                    url=url,
                    client=client,
                )
                print(f"[发现] {card.name}: {card.description}")
                for skill in card.skills:
                    print(f"  - {skill.name}: {skill.description}")
            except Exception as e:
                print(f"[警告] 无法连接 {url}: {e}")

    async def delegate_task(self, agent_name: str, task_text: str) -> str:
        """向指定 Agent 委派任务"""
        agent = self.agents.get(agent_name)
        if not agent or not agent.client:
            raise ValueError(f"Agent '{agent_name}' 未注册或不可用")

        request = SendMessageRequest(
            id=f"orchestrator-{agent_name}-001",
            params=MessageSendParams(
                message=Message(
                    role="user",
                    parts=[TextPart(type="text", text=task_text)],
                )
            ),
        )

        response = await agent.client.send_message(request)
        result = response.root.result

        if result.status.state == "completed" and result.artifacts:
            texts = []
            for artifact in result.artifacts:
                for part in artifact.parts:
                    if hasattr(part.root, "text"):
                        texts.append(part.root.text)
            return "\n".join(texts)
        elif result.status.state == "input-required":
            # 处理需要额外输入的情况
            return f"[需要补充输入] {result.status.message}"
        else:
            return f"[任务状态: {result.status.state}]"

    async def execute_plan(self, tasks: list[tuple[str, str]]) -> dict[str, str]:
        """并行执行多个子任务"""
        results = {}
        coroutines = [
            self.delegate_task(agent_name, task_text)
            for agent_name, task_text in tasks
        ]
        responses = await asyncio.gather(*coroutines, return_exceptions=True)
        for (agent_name, _), response in zip(tasks, responses):
            if isinstance(response, Exception):
                results[agent_name] = f"[错误] {response}"
            else:
                results[agent_name] = response
        return results

    async def close(self):
        await self.httpx_client.aclose()


async def main():
    orchestrator = MultiAgentOrchestrator(
        agent_urls=[
            "http://localhost:9001",  # Weather Agent
            "http://localhost:9002",  # Calendar Agent
            "http://localhost:9003",  # Flight Agent
        ]
    )

    await orchestrator.discover_agents()

    # 并行委派任务
    results = await orchestrator.execute_plan([
        ("Weather Agent", "伦敦下周三的天气预报"),
        ("Calendar Agent", "检查下周三是否有日程冲突"),
        ("Flight Agent", "搜索下周三北京到伦敦的航班"),
    ])

    print("\n=== 汇总结果 ===")
    for agent_name, result in results.items():
        print(f"\n[{agent_name}]:")
        print(f"  {result}")

    await orchestrator.close()


if __name__ == "__main__":
    asyncio.run(main())
```

### 8.2 Java/Spring 实现示例

#### 8.2.1 生态概览

| 组件 | Maven 坐标 | 最新版本 | 说明 |
|------|-----------|---------|------|
| **官方 Java SDK** | `org.a2aproject.sdk:a2a-java-sdk-*` | **1.0.0.Final** | Red Hat + Google 合作，参考实现基于 Quarkus |
| **Spring AI A2A** | `org.springaicommunity:spring-ai-a2a-server-autoconfigure` | **0.3.0** | Spring Boot 4.0+ / Spring AI 2.0 集成 |
| **a2ajava (社区)** | `io.github.vishalmysore:a2ajava` | 0.0.7.2 | 社区实现，同时支持 A2A + MCP |

> **注意**：从 1.0.0.Beta1 起，`groupId` 从 `io.github.a2asdk` 变更为 `org.a2aproject.sdk`，Java package 从 `io.a2a.*` 变更为 `org.a2aproject.sdk.*`。

#### 8.2.2 官方 A2A Java SDK -- Maven 依赖

```xml
<!-- BOM -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.a2aproject.sdk</groupId>
            <artifactId>a2a-java-sdk-bom</artifactId>
            <version>1.0.0.Final</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- Server (JSON-RPC transport，参考实现基于 Quarkus) -->
<dependency>
    <groupId>org.a2aproject.sdk</groupId>
    <artifactId>a2a-java-sdk-reference-jsonrpc</artifactId>
</dependency>

<!-- Server (gRPC transport) -->
<dependency>
    <groupId>org.a2aproject.sdk</groupId>
    <artifactId>a2a-java-sdk-reference-grpc</artifactId>
</dependency>

<!-- Server (REST transport) -->
<dependency>
    <groupId>org.a2aproject.sdk</groupId>
    <artifactId>a2a-java-sdk-reference-rest</artifactId>
</dependency>

<!-- Client -->
<dependency>
    <groupId>org.a2aproject.sdk</groupId>
    <artifactId>a2a-java-sdk-client</artifactId>
</dependency>

<!-- Client Transport (JSON-RPC) -->
<dependency>
    <groupId>org.a2aproject.sdk</groupId>
    <artifactId>a2a-java-sdk-client-transport-jsonrpc</artifactId>
</dependency>
```

#### 8.2.3 Server 端实现（官方 SDK + Quarkus）

**Step 1: 创建 AgentCard**

```java
import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.spec.*;

@ApplicationScoped
public class WeatherAgentCardProducer {

    private static final String AGENT_URL = "http://localhost:10001";

    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {
        return AgentCard.builder()
                .name("Weather Agent")
                .description("Helps with weather")
                .supportedInterfaces(List.of(
                        new AgentInterface(TransportProtocol.JSONRPC.asString(), AGENT_URL)))
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .build())
                .defaultInputModes(Collections.singletonList("text"))
                .defaultOutputModes(Collections.singletonList("text"))
                .skills(Collections.singletonList(AgentSkill.builder()
                        .id("weather_search")
                        .name("Search weather")
                        .description("Helps with weather in cities")
                        .tags(Collections.singletonList("weather"))
                        .examples(List.of("weather in LA, CA"))
                        .build()))
                .build();
    }
}
```

**Step 2: 创建 AgentExecutor**

```java
import org.a2aproject.sdk.server.agentexecution.*;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.*;

@ApplicationScoped
public class WeatherAgentExecutorProducer {

    @Inject
    WeatherAgent weatherAgent;

    @Produces
    public AgentExecutor agentExecutor() {
        return new AgentExecutor() {

            @Override
            public void execute(RequestContext context, AgentEmitter agentEmitter)
                    throws JSONRPCError {
                if (context.getTask() == null) {
                    agentEmitter.submit();
                }
                agentEmitter.startWork();

                // 从 Message 中提取用户文本
                String userMessage = extractText(context.getMessage());
                String response = weatherAgent.chat(userMessage);

                // 构建响应并完成任务
                TextPart responsePart = new TextPart(response);
                agentEmitter.addArtifact(List.of(responsePart));
                agentEmitter.complete();
            }

            @Override
            public void cancel(RequestContext context, AgentEmitter agentEmitter)
                    throws JSONRPCError {
                Task task = context.getTask();
                if (task.getStatus().state() == TaskState.CANCELED
                        || task.getStatus().state() == TaskState.COMPLETED) {
                    throw new TaskNotCancelableError();
                }
                agentEmitter.cancel();
            }

            private String extractText(Message message) {
                StringBuilder sb = new StringBuilder();
                for (Part<?> part : message.parts()) {
                    if (part instanceof TextPart textPart) {
                        sb.append(textPart.text());
                    }
                }
                return sb.toString();
            }
        };
    }
}
```

#### 8.2.4 Client 端实现（官方 SDK）

```java
import org.a2aproject.sdk.client.*;
import org.a2aproject.sdk.client.events.*;
import org.a2aproject.sdk.client.transport.jsonrpc.*;
import org.a2aproject.sdk.spec.*;

// 1. 解析远端 Agent Card
AgentCard agentCard = A2ACardResolver.builder()
        .baseUrl("http://localhost:10001")
        .build()
        .getAgentCard();

// 2. 配置 Client
ClientConfig clientConfig = new ClientConfig.Builder()
        .setAcceptedOutputModes(List.of("text"))
        .build();

// 3. 设置事件消费者
List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(
    (event, card) -> {
        if (event instanceof MessageEvent me) {
            System.out.println("Message: " + me.getMessage());
        } else if (event instanceof TaskEvent te) {
            System.out.println("Task: " + te.getTask().getStatus().state());
        } else if (event instanceof TaskUpdateEvent ue) {
            System.out.println("Update: " + ue.getTask().getStatus().state());
        }
    }
);

// 4. 构建并发送消息
Client client = Client.builder(agentCard)
        .clientConfig(clientConfig)
        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
        .addConsumers(consumers)
        .streamingErrorHandler(error -> System.err.println(error.getMessage()))
        .build();

Message message = A2A.toUserMessage("What's the weather in Beijing?");
client.sendMessage(message);

// 任务操作
Task task = client.getTask(new TaskQueryParams("task-1234"));
Task cancelled = client.cancelTask(new TaskIdParams("task-1234"));
```

#### 8.2.5 Spring AI A2A 集成

**架构**：

```
Your Spring Boot App
├── ChatClient, AgentCard, AgentExecutor (your beans)
├── DefaultAgentExecutor (bridges A2A <-> ChatClient)
└── A2A Controllers (auto-configured)
    ├── POST /           -> MessageController (sendMessage)
    ├── GET /card        -> AgentCardController (discovery)
    └── GET /tasks/{id}  -> TaskController (status)
```

**Maven 依赖**：

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-a2a-server-autoconfigure</artifactId>
    <version>0.3.0</version>
</dependency>
```

**application.properties**：

```properties
server.servlet.context-path=/weather
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
spring.ai.anthropic.chat.options.model=claude-sonnet-4-5-20250929
```

**Agent 配置类（Server 端）**：

```java
@Configuration
public class WeatherAgentConfiguration {

    @Bean
    public AgentCard agentCard(@Value("${server.port:8080}") int port,
            @Value("${server.servlet.context-path:/}") String contextPath) {
        return new AgentCard.Builder()
            .name("Weather Agent")
            .description("Provides weather information for cities")
            .url("http://localhost:" + port + contextPath + "/")
            .version("1.0.0")
            .capabilities(new AgentCapabilities.Builder().streaming(false).build())
            .defaultInputModes(List.of("text"))
            .defaultOutputModes(List.of("text"))
            .skills(List.of(new AgentSkill.Builder()
                .id("weather_search")
                .name("Search weather")
                .description("Get temperature for any city")
                .tags(List.of("weather"))
                .examples(List.of("What's the weather in London?"))
                .build()))
            .protocolVersion("0.3.0")
            .build();
    }

    @Bean
    public AgentExecutor agentExecutor(ChatClient.Builder chatClientBuilder,
            WeatherTools weatherTools) {
        ChatClient chatClient = chatClientBuilder.clone()
            .defaultSystem("You are a weather assistant. Use the temperature tool.")
            .defaultTools(weatherTools)
            .build();

        return new DefaultAgentExecutor(chatClient, (chat, requestContext) -> {
            String userMessage = DefaultAgentExecutor
                    .extractTextFromMessage(requestContext.getMessage());
            return chat.prompt().user(userMessage).call().content();
        });
    }
}

@Service
class WeatherTools {

    @Tool(description = "Get temperature for a city")
    public String getTemperature(
            @ToolParam(description = "City name") String city) {
        // 实际实现调用天气 API
        return "25°C, Sunny";
    }
}
```

启动后自动暴露：
- `POST /weather/` -- JSON-RPC sendMessage 请求
- `GET /weather/.well-known/agent-card.json` -- Agent Card 发现
- `GET /weather/card` -- Agent Card 备用端点

**Multi-Agent Orchestrator（Client 端）**：

```xml
<!-- 需要额外引入 A2A SDK Client -->
<dependency>
    <groupId>io.github.a2asdk</groupId>
    <artifactId>a2a-java-sdk-client</artifactId>
    <version>0.3.3.Final</version>
</dependency>
```

```java
@Service
public class RemoteAgentConnections {

    private final Map<String, AgentCard> agentCards = new HashMap<>();

    public RemoteAgentConnections(
            @Value("${remote.agents.urls}") List<String> agentUrls) throws Exception {
        for (String url : agentUrls) {
            String path = new URI(url).getPath();
            AgentCard card = A2A.getAgentCard(
                    url, path + ".well-known/agent-card.json", null);
            this.agentCards.put(card.name(), card);
        }
    }

    @Tool(description = "Sends a task to a remote agent")
    public String sendMessage(
            @ToolParam(description = "Agent name") String agentName,
            @ToolParam(description = "Task description") String task) throws Exception {

        AgentCard agentCard = this.agentCards.get(agentName);

        Message message = new Message.Builder()
            .role(Message.Role.USER)
            .parts(List.of(new TextPart(task, null)))
            .build();

        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        Client client = Client.builder(agentCard)
            .clientConfig(new ClientConfig.Builder()
                .setAcceptedOutputModes(List.of("text"))
                .build())
            .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
            .addConsumers(List.of(consumer -> {
                if (consumer instanceof TextPart textPart) {
                    responseFuture.complete(textPart.getText());
                }
            }))
            .build();

        client.sendMessage(message);
        return responseFuture.get(60, TimeUnit.SECONDS);
    }

    public String getAgentDescriptions() {
        return agentCards.values().stream()
            .map(card -> card.name() + ": " + card.description())
            .collect(Collectors.joining("\n"));
    }
}

@Configuration
public class HostAgentConfiguration {

    @Bean
    public ChatClient routingChatClient(ChatClient.Builder chatClientBuilder,
            RemoteAgentConnections remoteAgentConnections) {

        String systemPrompt = """
            You coordinate tasks across specialized agents.
            Available agents:
            %s
            Use the sendMessage tool to delegate tasks.
            """.formatted(remoteAgentConnections.getAgentDescriptions());

        return chatClientBuilder
            .defaultSystem(systemPrompt)
            .defaultTools(remoteAgentConnections)
            .build();
    }
}
```

#### 8.2.6 从零实现 A2A（Spring WebFlux SSE Streaming Server）

如果需要完全自定义实现（学习目的或极度定制化场景）：

```java
@RestController
public class A2AController {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Agent Card 端点
    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> agentCard() {
        return Map.of(
            "name", "My Agent",
            "description", "A custom A2A agent",
            "version", "1.0.0",
            "protocolVersion", "1.0.0",
            "capabilities", Map.of("streaming", true),
            "defaultInputModes", List.of("text"),
            "defaultOutputModes", List.of("text"),
            "supportedInterfaces", List.of(
                Map.of("protocol", "jsonrpc",
                       "url", "http://localhost:8080")),
            "skills", List.of(Map.of(
                "id", "chat",
                "name", "Chat",
                "description", "General chat capability"
            ))
        );
    }

    // JSON-RPC 端点 (非 Streaming)
    @PostMapping(value = "/", consumes = "application/json",
                 produces = "application/json")
    public Map<String, Object> handleJsonRpc(@RequestBody Map<String, Object> request) {
        String method = (String) request.get("method");
        Object id = request.get("id");
        Map<String, Object> params = (Map<String, Object>) request.get("params");

        return switch (method) {
            case "message/send" -> handleSendMessage(id, params);
            case "tasks/get" -> handleGetTask(id, params);
            case "tasks/cancel" -> handleCancelTask(id, params);
            default -> jsonRpcError(id, -32601, "Method not found");
        };
    }

    // SSE Streaming 端点 (Spring WebFlux)
    @PostMapping(value = "/", consumes = "application/json",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> handleStreamingJsonRpc(
            @RequestBody Map<String, Object> request) {
        String taskId = UUID.randomUUID().toString();

        return Flux.create(sink -> {
            // 发送 task submitted 事件
            sink.next(sse("task.status", Map.of(
                "taskId", taskId,
                "status", Map.of("state", "submitted")
            )));

            // 发送 working 状态
            sink.next(sse("task.status", Map.of(
                "taskId", taskId,
                "status", Map.of("state", "working")
            )));

            // 模拟 Agent 处理并发送 artifact
            String response = processWithLLM(request);
            sink.next(sse("task.artifact", Map.of(
                "taskId", taskId,
                "artifact", Map.of(
                    "parts", List.of(Map.of("type", "text", "text", response))
                )
            )));

            // 完成
            sink.next(sse("task.status", Map.of(
                "taskId", taskId,
                "status", Map.of("state", "completed")
            )));

            sink.complete();
        });
    }

    private ServerSentEvent<String> sse(String event, Object data) {
        try {
            return ServerSentEvent.<String>builder()
                .event(event)
                .data(objectMapper.writeValueAsString(data))
                .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

**纯 Java HttpClient 实现 A2A Client**：

```java
public class SimpleA2AClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String serverUrl;

    public SimpleA2AClient(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    // 获取 Agent Card
    public Map<String, Object> getAgentCard() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(serverUrl + "/.well-known/agent-card.json"))
            .GET()
            .build();
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), Map.class);
    }

    // 发送消息 (JSON-RPC)
    public Map<String, Object> sendMessage(String text) throws Exception {
        Map<String, Object> jsonRpcRequest = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "message/send",
            "params", Map.of(
                "message", Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("type", "text", "text", text))
                )
            )
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(serverUrl + "/"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(jsonRpcRequest)))
            .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), Map.class);
    }

    // SSE Streaming 消费
    public void sendMessageStreaming(String text,
            Consumer<String> onEvent) throws Exception {
        Map<String, Object> jsonRpcRequest = Map.of(
            "jsonrpc", "2.0",
            "id", UUID.randomUUID().toString(),
            "method", "message/stream",
            "params", Map.of(
                "message", Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("type", "text", "text", text))
                )
            )
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(serverUrl + "/"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(jsonRpcRequest)))
            .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
            .thenAccept(response -> {
                response.body().forEach(line -> {
                    if (line.startsWith("data:")) {
                        onEvent.accept(line.substring(5).trim());
                    }
                });
            });
    }
}
```

#### 8.2.7 Java 方案选型建议

| 场景 | 推荐方案 | 理由 |
|------|----------|------|
| Spring Boot 4.x + Spring AI 2.x | `spring-ai-a2a` + 官方 SDK | 最省力，Autoconfigure 自动搞定 Server 端 |
| Quarkus 项目 | 官方 A2A Java SDK | 原生支持，参考实现即基于 Quarkus |
| Spring Boot 3.x（暂不升级 4.0） | 直接用官方 SDK + 手动配 Controller | `spring-ai-a2a` 要求 Spring Boot 4.0+ |
| 需要同时支持 A2A + MCP | `a2ajava` (vishalmysore) | 一套注解同时暴露两种协议 |
| 极度定制化 / 学习目的 | 从零用 Spring WebFlux 实现 | 参考 8.2.6 的代码 |

---

## 九、总结与展望

### 9.1 核心要点

**MCP 是 Agent 的"手"（操作工具），A2A 是 Agent 的"嘴"（与同伴对话），ACP 已合并入 A2A 成为其一部分。三者共同构成 AI Agent 通信的完整协议栈，全部归 Linux Foundation 治理。**

企业完整的 Agent 协议栈：

```
MCP（工具接入）+ A2A（Agent 协作）+ ACP/UCP（商业交易）
```

### 9.2 三种架构模式

1. **MCP-Dominant**：单 Agent 中心编排 + 多 MCP Server 工具连接。适合 Workflow 自动化、内部 Copilot。
2. **A2A-Based Multi-Agent**：多个专业 Agent 通过 A2A 对等协作。适合分布式专家系统。
3. **Hybrid MCP + A2A**（推荐的生产架构）：A2A 做 Agent 间编排，MCP 做 Agent 内部工具调用。

### 9.3 未来 Roadmap（2026 年后）

1. **Interoperability Specification**：标准化不同实现间的兼容性测试
2. **Registry 标准化**：定义标准 Registry API（含联邦化 Peering）
3. **Testing and Tooling**：标准化合规性测试套件和开发者工具
4. **Security and Deployment Best Practices**：文档化生产级安全模式

### 9.4 关键注意事项

1. **Maven 坐标迁移**：A2A Java SDK 在 1.0.0.Beta1 后 `groupId` 从 `io.github.a2asdk` 改为 `org.a2aproject.sdk`
2. **Spring Boot 版本要求**：`spring-ai-a2a` 需要 Spring Boot 4.0+ 和 Spring AI 2.0.0-M2+
3. **Client 端集成尚未 Autoconfigure**：Spring AI A2A 当前聚焦 Server 端
4. **Auth 机制仍在演进**：当前 AgentCard 可声明 authentication 字段，但 OAuth/JWT 实际集成需自行实现
5. **AgentCard 不是 Contract Test**：描述能力接口但不保证行为稳定性

---

## 参考资料

### 协议规范与官方资源
- [A2A Protocol Specification](https://github.com/a2aproject/A2A/blob/main/docs/specification.md)
- [A2A Agent Discovery](https://github.com/a2aproject/A2A/blob/main/docs/topics/agent-discovery.md)
- [A2A Samples](https://github.com/a2aproject/a2a-samples)
- [Google Developers Blog - A2A](https://developers.googleblog.com/en/a2a-a-new-era-of-agent-interoperability)
- [Linux Foundation - A2A 1st Anniversary](https://www.linuxfoundation.org/press/a2a-protocol-surpasses-150-organizations-lands-in-major-cloud-platforms-and-sees-enterprise-production-use-in-first-year)

### Java/Spring 生态
- [A2A Java SDK (GitHub)](https://github.com/a2aproject/a2a-java)
- [Spring AI A2A (GitHub)](https://github.com/spring-ai-community/spring-ai-a2a)
- [Spring Blog - A2A Integration](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration)
- [Quarkus A2A Java SDK 1.0.0 Release](https://quarkus.io/blog/a2a-java-sdk-1-0-0-beta1-released)

### 协议对比与分析
- [Stride - A2A vs MCP: When to Use Which](https://www.stride.build/blog/agent-to-agent-a2a-vs-model-context-protocol-mcp-when-to-use-which)
- [Auth0 - MCP vs A2A](https://auth0.com/blog/mcp-vs-a2a)
- [ACP Official - MCP and A2A](https://agentcommunicationprotocol.dev/about/mcp-and-a2a)
- [IBM - Agent Communication Protocol](https://www.ibm.com/think/topics/agent-communication-protocol)
- [LF AI & Data - ACP Joins A2A](https://lfaidata.foundation/communityblog/2025/08/29/acp-joins-forces-with-a2a-under-the-linux-foundations-lf-ai-data)

### 架构与最佳实践
- [Solo.io - Agent Discovery, Naming, and Resolution](https://www.solo.io/blog/agent-discovery-naming-and-resolution---the-missing-pieces-to-a2a)
- [Solo.io - Agent Mesh for Enterprise Agents](https://www.solo.io/blog/agent-mesh-for-enterprise-agents)
- [TrueFoundry - Multi-Agent A2A Governance Gateway](https://www.truefoundry.com/blog/multi-agent-a2a-governance-gateway)
- [Cisco Outshift - AI Observability Multi-Agent Systems](https://outshift.cisco.com/blog/ai-ml/ai-observability-multi-agent-systems-opentelemetry)
- [Tyk - A2A Protocol Architecture](https://tyk.io/learning-center/a2a-protocol-architecture-and-technical-specification)

### 批判与替代方案
- [HiveMQ - A2A at Enterprise Scale (Part 1)](https://www.hivemq.com/blog/a2a-enterprise-scale-agentic-ai-collaboration-part-1)
- [HiveMQ - Why MQTT (Part 3)](https://www.hivemq.com/blog/why-mqtt-best-suited-for-scale-agentic-ai-collaboration-part-3)
- [HiveMQ - A2A over MQTT (Part 4)](https://www.hivemq.com/blog/example-of-a2a-over-mqtt-scale-agentic-ai-collaboration-part-4)
- [EMQX - MQTT as Missing Infrastructure for Agentic AI](https://www.emqx.com/en/blog/why-mqtt-is-the-missing-infrastructure-layer-for-agentic-ai)
- [Kafka - Agentic AI with A2A and MCP](https://www.kai-waehner.de/blog/2025/05/26/agentic-ai-with-the-agent2agent-protocol-a2a-and-mcp-using-apache-kafka-as-event-broker)

### 生态与行业
- [GitHub Blog - MCP Joins the Linux Foundation](https://github.blog/open-source/maintainers/mcp-joins-the-linux-foundation-what-this-means-for-developers-building-the-next-era-of-ai-tools-and-agents)
- [Digital Applied - AI Agent Protocol Ecosystem Map 2026](https://www.digitalapplied.com/blog/ai-agent-protocol-ecosystem-map-2026-mcp-a2a-acp-ucp)
- [Google Cloud Blog - A2A Protocol Upgrade](https://cloud.google.com/blog/products/ai-machine-learning/agent2agent-protocol-is-getting-an-upgrade)
- [DeepLearning.AI - A2A Course](https://www.deeplearning.ai/courses/a2a-the-agent2agent-protocol)