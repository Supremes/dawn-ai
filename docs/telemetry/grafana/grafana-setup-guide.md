# Grafana 监控配置向导

> 适用于 Dawn AI 项目，基于 Spring Boot Actuator + Prometheus + Grafana 监控栈。

---

## 一、数据流向

```
Spring Boot App
    │
    │  暴露指标数据（HTTP）
    ▼
/actuator/prometheus   ← 端点返回 Prometheus 格式的纯文本指标
    │
    │  Prometheus 定时来"拉取"（scrape，默认每 15s）
    ▼
Prometheus（时序数据库）
    │
    │  Grafana 查询这个数据库
    ▼
Grafana（可视化面板）
```

| 组件 | 职责 |
|------|------|
| Spring Boot Actuator | 把应用内部状态（内存、请求数等）变成可读数据 |
| Prometheus | 定时抓取并存储这些数据（时序数据库） |
| Grafana | 查询 Prometheus，画成图表 |

---

## 二、逐层验证（调试思路）

### 第 1 层：Spring Boot 是否暴露了指标

```bash
curl http://localhost:8080/actuator/prometheus
```

✅ 正常：返回一堆 `# HELP xxx` 开头的文本  
❌ 报错：检查以下两个地方

**pom.xml 需要的依赖：**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

**application.yml 需要开放端点：**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

---

### 第 2 层：Prometheus 是否正常抓取

访问 `http://localhost:9090` → 顶部菜单 **Status → Targets**

✅ 正常：`dawn-ai` 的 State 显示 **UP**（绿色）  
❌ 异常：State 显示 **DOWN**，Prometheus 连不上 App

检查 `prometheus.yml`：
```yaml
scrape_configs:
  - job_name: 'dawn-ai'
    static_configs:
      - targets: ['app:8080']       # docker 网络内用服务名
    metrics_path: '/actuator/prometheus'
```

> ⚠️ `targets` 地址要从 **Prometheus 容器的视角**来写：
> - 都在 docker-compose 里 → 用服务名 `app:8080`
> - Prometheus 在容器但 App 在本机 → 用 `host.docker.internal:8080`
> - 都在本机直接跑 → 用 `localhost:8080`

---

### 第 3 层：在 Prometheus UI 直接验证数据

访问 `http://localhost:9090`，在搜索框输入：

```promql
up{job="dawn-ai"}
```
结果为 `1` 表示 App 在线。再试试：
```promql
jvm_memory_used_bytes
```
能看到数据说明指标采集正常。

---

### 第 4 层：Grafana 配置数据源

**手动方式（理解原理用）：**

1. 登录 `http://localhost:3000`（admin / admin123）
2. 左侧菜单 → **Connections → Data sources → Add data source**
3. 选 **Prometheus**
4. URL 填 `http://prometheus:9090`（docker 内）
5. 点 **Save & Test** → 绿色 ✅ 表示连通

**Provisioning 方式（本项目使用的方案）：**

```yaml
# grafana/provisioning/datasources/prometheus.yml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090
    isDefault: true
```
Grafana 启动时自动读取，等价于手动操作第 2-5 步，且纳入版本控制。

---

### 第 5 层：Grafana 配置 Dashboard

**手动方式：**

1. 左侧菜单 → **Dashboards → Import**
2. 输入社区 Dashboard ID（如 `4701` JVM Micrometer 经典面板）
3. 选择数据源 → Import

**Provisioning 方式（本项目使用的方案）：**

需要两个文件配合：

```yaml
# grafana/provisioning/dashboards/default.yml
# 告诉 Grafana 去哪里加载 Dashboard JSON 文件
apiVersion: 1
providers:
  - name: Dawn AI
    type: file
    options:
      path: /var/lib/grafana/dashboards   # 容器内路径
```

```
# grafana/dashboards/spring-boot.json
# 实际的面板定义（可从 Grafana UI 导出，或从社区下载）
```

---

## 三、docker-compose 挂载关系

```yaml
grafana:
  volumes:
    - grafana_data:/var/lib/grafana                      # 持久化用户数据
    - ./grafana/provisioning:/etc/grafana/provisioning   # 自动配置数据源 + 面板来源
    - ./grafana/dashboards:/var/lib/grafana/dashboards   # 面板 JSON 文件
```

本机路径 `./grafana/provisioning` 映射到容器内 `/etc/grafana/provisioning`，这是 Grafana 启动时固定读取的目录。

**本项目目录结构：**
```
grafana/
├── provisioning/
│   ├── datasources/
│   │   └── prometheus.yml     ← 自动注册 Prometheus 数据源
│   └── dashboards/
│       └── default.yml        ← 指定 Dashboard JSON 加载路径
└── dashboards/
    └── spring-boot.json       ← JVM + HTTP + App 状态面板
```

---

## 四、完整启动流程

```bash
# 1. 启动基础设施
docker-compose up -d postgres redis

# 2. 启动监控栈
docker-compose up -d prometheus grafana

# 3. 启动应用
export OPENAI_API_KEY=sk-xxx
./mvnw spring-boot:run

# 4. 逐层验证
curl http://localhost:8080/actuator/prometheus | head -5
# 浏览器: http://localhost:9090/targets    → 检查 Prometheus 抓取状态
# 浏览器: http://localhost:3000            → 查看 Grafana 面板（Dawn AI 文件夹）

# 如果只重启 Grafana（配置有变更时）
docker-compose up -d --force-recreate grafana
```

---

## 五、常见问题速查

| 现象 | 原因 | 解决 |
|------|------|------|
| Grafana 面板空白但有数据源 | 时间范围不对 | 右上角改成 `Last 5 minutes` |
| Prometheus Targets 显示 DOWN | 网络不通 / 地址写错 | 检查 `prometheus.yml` 的 targets |
| `/actuator/prometheus` 返回 404 | 端点未暴露 | 检查 `application.yml` 的 `include` 列表 |
| Grafana 启动后面板不见 | Volume 未挂载 | 检查 docker-compose volumes 配置 |
| 数据源连接失败 | URL 写了 localhost 但在容器内 | 改成服务名 `prometheus:9090` |
| 修改 provisioning 文件后不生效 | 容器未重建 | 执行 `--force-recreate grafana` |
