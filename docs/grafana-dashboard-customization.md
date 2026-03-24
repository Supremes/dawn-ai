# Grafana Dashboard JSON 自定义指南

> 本文说明如何为 Dawn AI 项目自定义 Grafana Dashboard，包含三种方式及最佳实践。

---

## 三种来源方式

### 方式一：从社区导入（最省事）

Grafana 官方社区 [grafana.com/grafana/dashboards](https://grafana.com/grafana/dashboards) 有大量现成模板。

**Spring Boot 最常用的几个：**

| Dashboard ID | 名称 | 适用场景 |
|---|---|---|
| `4701` | JVM Micrometer | JVM 内存/GC/线程，经典必装 |
| `12900` | Spring Boot 2.1+ | HTTP请求、数据库连接池 |
| `11378` | Spring Boot Statistics | 综合监控 |

**下载 JSON 步骤：**
1. 打开对应页面，点 **Download JSON**
2. 保存到 `grafana/dashboards/` 目录
3. 重启 Grafana 自动加载（`docker-compose up -d --force-recreate grafana`）

---

### 方式二：在 UI 里搭，然后导出（推荐工作流）

```
在 Grafana UI 里拖拽搭建
        ↓
Dashboard 右上角 → Share → Export → Save to file
        ↓
把 JSON 放到 grafana/dashboards/
        ↓
纳入 git 版本控制
```

**搭建时关键操作：**
- **Add panel** → 选图表类型（Time series / Stat / Gauge）
- **Metrics browser** 里直接搜 metric 名，如输入 `jvm_` 会自动提示
- 用 `rate(xxx[1m])` 把计数器转成速率

---

### 方式三：理解 JSON 结构，手写/修改

JSON 的核心结构只有三层：

```json
{
  "title": "Dawn AI",
  "panels": [
    {
      "title": "HTTP 请求速率",
      "type": "timeseries",
      "gridPos": { "x": 0, "y": 0, "w": 12, "h": 8 },
      "targets": [
        {
          "expr": "rate(http_server_requests_seconds_count[1m])",
          "legendFormat": "{{method}} {{uri}}"
        }
      ]
    }
  ],
  "templating": {
    "list": [
      {
        "name": "instance",
        "type": "query",
        "query": "label_values(up, instance)"
      }
    ]
  }
}
```

**gridPos 坐标系说明：** 24列等宽格子，`w=12` 是半屏宽，`h` 单位约 30px，`x/y` 是左上角坐标。

---

## Dawn AI 项目推荐的 PromQL

在已有基础 JVM + HTTP 面板之上，可按需补充：

```promql
# AI 工具调用次数（Tool 执行次数）
rate(method_timed_seconds_count{class=~".*Tool"}[1m])

# HTTP 错误率
rate(http_server_requests_seconds_count{status=~"5.."}[1m])
  / rate(http_server_requests_seconds_count[1m])

# Redis 连接（Lettuce）
lettuce_command_completion_seconds_count

# 数据库连接池（HikariCP）
hikaricp_connections_active
hikaricp_connections_pending
```

---

## 最佳实践

| 实践 | 原因 |
|------|------|
| Dashboard JSON 纳入 git | 可复现，团队共享，不依赖 volume 持久化 |
| 用变量 `$instance` 做筛选 | 多实例部署时可以切换 |
| 面板加 `description` 字段 | 鼠标悬停显示说明，方便他人理解 |
| `"refresh": "30s"` 自动刷新 | 实时监控不需要手动刷新 |
| 不同关注点用 Row 分组 | JVM / HTTP / Business 分层清晰 |
| 先在 UI 调好再导出 JSON | 比手写 JSON 快 10 倍 |

**推荐工作流：** 社区 Dashboard 作为起点 → UI 里按需增删改 → 导出 JSON 提交到 git ✅

---

## 相关文件

```
grafana/
├── provisioning/
│   ├── datasources/prometheus.yml
│   └── dashboards/default.yml
└── dashboards/
    └── spring-boot.json        ← 当前项目自定义面板
```

参考：[grafana-setup-guide.md](./grafana-setup-guide.md)
