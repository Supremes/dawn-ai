package com.dawn.ai;

import com.dawn.ai.dto.ChatRequest;
import com.dawn.ai.dto.ChatResponse;
import com.dawn.ai.dto.RagRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

public class RestAPITest {
    public static void main(String[] args) {
        testRAGAPI();
    }

    public static void testChatAPI() {
        String baseUrl = "http://localhost:8080/api/v1/chat";
        RestTemplate restTemplate = new RestTemplate();

        ChatRequest request = new ChatRequest();
        request.setMessage("hello from RestTemplate");
        request.setSessionId("local-test-session");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<ChatRequest> requestEntity = new HttpEntity<>(request, headers);

        ResponseEntity<ChatResponse> postResponse =
                restTemplate.postForEntity(baseUrl, requestEntity, ChatResponse.class);
        System.out.println("POST /api/v1/chat => " + postResponse.getBody());

        ResponseEntity<String> getResponse = restTemplate.getForEntity(
                baseUrl + "/simple?message={message}",
                String.class,
                "hello from get");
        System.out.println("GET /api/v1/chat/simple => " + getResponse.getBody());
    }

    public static void testRAGAPI() {
        String baseUrl = "http://localhost:8080/api/v1/rag/ingest";
        RestTemplate restTemplate = new RestTemplate();

        RagRequest request = new RagRequest();
        request.setContent(CONTENT2);


        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<RagRequest> requestEntity = new HttpEntity<>(request, headers);

        ResponseEntity<Map> postResponse =
                restTemplate.postForEntity(baseUrl, requestEntity, Map.class);
        System.out.println("POST /api/v1/rag/ingest => " + postResponse.getBody());
    }

    public static final String CONTENT1 = """
                AgentScope Java 是一个面向智能体的编程框架，用于构建 LLM 驱动的应用程序。它提供了创建智能体所需的一切：ReAct 推理、工具调用、内存管理、多智能体协作等。
                
                核心亮点
                智能体自主，全程可控
                AgentScope 采用 ReAct（推理-行动）范式，使智能体能够自主规划和执行复杂任务。与传统的工作流方法不同，ReAct 智能体能够动态决定使用哪些工具以及何时使用，实时适应不断变化的需求。
                
                然而，在生产环境中，没有控制的自主性是一种隐患。AgentScope 提供了全面的运行时干预机制：
                
                安全中断 - 在任意时刻暂停智能体执行，同时保留完整的上下文和工具状态，支持无数据丢失的无缝恢复
                
                优雅取消 - 终止长时间运行或无响应的工具调用，而不会破坏智能体状态，允许立即恢复和重定向
                
                人机协作 - 通过 Hook 系统在任何推理步骤注入修正、额外上下文或指导，保持人类对关键决策的监督
                
                内置工具
                AgentScope 包含生产就绪的工具，解决智能体开发中的常见挑战：
                
                PlanNotebook - 结构化任务管理系统，将复杂目标分解为有序、可追踪的步骤。智能体可以创建、修改、暂停和恢复多个并发计划，确保多步骤工作流的系统化执行。
                
                结构化输出 - 自纠错输出解析器，保证类型安全的响应。当 LLM 输出偏离预期格式时，系统会自动检测错误并引导模型生成有效输出，将结果直接映射到 Java POJO，无需手动解析。
                
                长期记忆 - 跨会话的持久化内存存储，支持语义搜索。支持自动管理、智能体控制记录或混合模式。支持多租户隔离，满足企业部署中智能体服务多用户的需求。
                
                RAG（检索增强生成） - 与企业知识库无缝集成。支持自托管的基于嵌入的检索和阿里云百炼等托管服务，使智能体响应基于权威数据源。
                
                无缝集成
                AgentScope 设计为与现有企业基础设施集成，无需大量修改：
                
                MCP 协议 - 与任何 MCP 兼容服务器集成，即时扩展智能体能力。连接到不断增长的 MCP 工具和服务生态系统——从文件系统和数据库到 Web 浏览器和代码解释器——无需编写自定义集成代码。
                
                A2A 协议 - 通过标准服务发现实现分布式多智能体协作。将智能体能力注册到 Nacos 或类似注册中心，允许智能体像调用微服务一样自然地发现和调用彼此。
                
                生产级别
                为企业部署需求而构建：
                
                高性能 - 基于 Project Reactor 的响应式架构确保非阻塞执行。GraalVM 原生镜像编译实现 200ms 冷启动时间，使 AgentScope 适用于 Serverless 和自动扩缩容环境。
                
                安全沙箱 - AgentScope Runtime 为不受信任的工具代码提供隔离的执行环境。包括用于 GUI 自动化、文件系统操作和移动设备交互的预构建沙箱，防止未授权访问系统资源。
                
                可观测性 - 原生集成 OpenTelemetry，实现整个智能体执行管道的分布式追踪。AgentScope Studio 为开发和生产环境提供可视化调试、实时监控和全面的日志记录。
                """;
    public static final String CONTENT2 = """
            消息（Message）
            解决的问题：智能体需要一种统一的数据结构来承载各种类型的信息——文本、图像、工具调用等。
            
            Message 是 AgentScope 最核心的数据结构，用于：
            
            在智能体之间交换信息
            
            在记忆中存储对话历史
            
            作为与 LLM API 交互的统一媒介
            
            核心字段：
            
            字段
            
            说明
            
            name
            
            发送者名称，多智能体场景用于区分身份
            
            role
            
            角色：USER、ASSISTANT、SYSTEM 或 TOOL
            
            content
            
            内容块列表，支持多种类型
            
            metadata
            
            可选的结构化数据
            
            内容类型：
            
            TextBlock - 纯文本
            
            ImageBlock / AudioBlock / VideoBlock - 多模态内容
            
            ThinkingBlock - 推理过程（用于推理模型）
            
            ToolUseBlock - LLM 发起的工具调用
            
            ToolResultBlock - 工具执行结果
            
            响应元信息：
            
            Agent 返回的消息包含额外的元信息，帮助理解执行状态：
            
            方法
            
            说明
            
            getGenerateReason()
            
            消息生成原因，用于判断后续操作
            
            getChatUsage()
            
            Token 用量统计（输入/输出 Token 数、耗时）
            
            GenerateReason 枚举值：
            
            值
            
            说明
            
            MODEL_STOP
            
            任务正常完成
            
            TOOL_SUSPENDED
            
            工具需要外部执行，等待提供结果
            
            REASONING_STOP_REQUESTED
            
            Reasoning 阶段被 Hook 暂停（HITL）
            
            ACTING_STOP_REQUESTED
            
            Acting 阶段被 Hook 暂停（HITL）
            
            INTERRUPTED
            
            Agent 被中断
            
            MAX_ITERATIONS
            
            达到最大迭代次数
            
            示例：
            
            // 创建文本消息
            Msg msg = Msg.builder()
                .name("user")
                .textContent("今天北京天气怎么样？")
                .build();
            
            // 创建多模态消息
            Msg imgMsg = Msg.builder()
                .name("user")
                .content(List.of(
                    TextBlock.builder().text("这张图片是什么？").build(),
                    ImageBlock.builder().source(new URLSource("https://example.com/photo.jpg")).build()
                ))
                .build();
            智能体（Agent）
            解决的问题：需要一个统一的抽象来封装”接收消息 → 处理 → 返回响应”的逻辑。
            
            Agent 接口定义了智能体的核心契约：
            
            public interface Agent {
                Mono<Msg> call(Msg msg);      // 处理消息，返回响应
                Flux<Msg> stream(Msg msg);    // 流式返回响应
                void interrupt();             // 中断执行
            }
            有状态设计
            AgentScope 中的 Agent 是有状态的对象。每个 Agent 实例持有自己的：
            
            Memory：对话历史
            
            Toolkit：工具集合及其状态
            
            配置：系统提示、模型设置等
            
            重要：由于 Agent 和 Toolkit 都是有状态的，同一个实例不能被并发调用。如果需要处理多个并发请求，应该为每个请求创建独立的 Agent 实例，或使用对象池管理。
            
            // ❌ 错误：多线程共享同一个 agent 实例
            ReActAgent agent = ReActAgent.builder()...build();
            executor.submit(() -> agent.call(msg1));  // 并发问题！
            executor.submit(() -> agent.call(msg2));  // 并发问题！
            
            // ✅ 正确：每个请求使用独立的 agent 实例
            executor.submit(() -> {
                ReActAgent agent = ReActAgent.builder()...build();
                agent.call(msg1);
            });
            ReActAgent
            ReActAgent 是框架提供的主要实现，使用 ReAct 算法（推理 + 行动循环）：
            
            ReActAgent agent = ReActAgent.builder()
                .name("Assistant")
                .model(DashScopeChatModel.builder()
                    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                    .modelName("qwen3-max")
                    .build())
                .sysPrompt("你是一个有帮助的助手。")
                .toolkit(toolkit)  // 可选：添加工具
                .build();
            
            // 调用智能体
            Msg response = agent.call(userMsg).block();
            详细配置请参考 创建 ReAct 智能体。
            
            工具（Tool）
            解决的问题：LLM 本身只能生成文本，无法执行实际操作。工具让智能体能够查询数据库、调用 API、执行计算等。
            
            AgentScope 中的”工具”是带有 @Tool 注解的 Java 方法，支持：
            
            实例方法、静态方法、类方法
            
            同步或异步调用
            
            流式或非流式返回
            
            示例：
            
            public class WeatherService {
                @Tool(name = "get_weather", description = "获取指定城市的天气")
                public String getWeather(
                        @ToolParam(name = "city", description = "城市名称") String city) {
                    // 调用天气 API
                    return "北京：晴，25°C";
                }
            }
            
            // 注册工具
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new WeatherService());
            重要：@ToolParam 必须显式指定 name 属性，因为 Java 运行时不保留方法参数名。
            
            记忆（Memory）
            解决的问题：智能体需要记住对话历史，才能进行有上下文的对话。
            
            Memory 管理对话历史，ReActAgent 会自动：
            
            将用户消息加入记忆
            
            将工具调用和结果加入记忆
            
            将智能体响应加入记忆
            
            在推理时读取记忆作为上下文
            
            默认使用 InMemoryMemory（内存存储）。如需跨会话持久化，请参考 状态管理。
            
            格式化器（Formatter）
            解决的问题：不同的 LLM 提供商有不同的 API 格式，需要一个适配层来屏蔽差异。
            
            Formatter 负责将 AgentScope 的消息转换为特定 LLM API 所需的格式，包括：
            
            提示词工程（添加系统提示、格式化多轮对话）
            
            消息验证
            
            多智能体场景的身份处理
            
            内置实现：
            
            DashScopeFormatter - 阿里云百炼（通义千问系列）
            
            OpenAIFormatter - OpenAI 及兼容 API
            
            格式化器根据 Model 类型自动选择，通常无需手动配置。
            
            钩子（Hook）
            解决的问题：需要在智能体执行的各个阶段插入自定义逻辑，如日志、监控、消息修改等。
            
            Hook 通过事件机制在 ReAct 循环的关键节点提供扩展点：
            
            事件类型
            
            触发时机
            
            可修改
            
            PreCallEvent
            
            智能体开始处理前
            
            ✓
            
            PostCallEvent
            
            智能体处理完成后
            
            ✓
            
            PreReasoningEvent
            
            调用 LLM 前
            
            ✓
            
            PostReasoningEvent
            
            LLM 返回后
            
            ✓
            
            ReasoningChunkEvent
            
            LLM 流式输出时
            
            -
            
            PreActingEvent
            
            执行工具前
            
            ✓
            
            PostActingEvent
            
            工具执行后
            
            ✓
            
            ActingChunkEvent
            
            工具流式输出时
            
            -
            
            ErrorEvent
            
            发生错误时
            
            -
            
            Hook 优先级：Hook 按优先级执行，数值越小优先级越高，默认 100。
            
            示例：
            
            Hook loggingHook = new Hook() {
                @Override
                public <T extends HookEvent> Mono<T> onEvent(T event) {
                    return switch (event) {
                        case PreCallEvent e -> {
                            System.out.println("智能体开始处理...");
                            yield Mono.just(event);
                        }
                        case ReasoningChunkEvent e -> {
                            System.out.print(e.getIncrementalChunk().getTextContent());  // 打印流式输出
                            yield Mono.just(event);
                        }
                        case PostCallEvent e -> {
                            System.out.println("处理完成: " + e.getFinalMessage().getTextContent());
                            yield Mono.just(event);
                        }
                        default -> Mono.just(event);
                    };
                }
            
                @Override
                public int priority() {
                    return 50;  // 高优先级
                }
            };
            
            ReActAgent agent = ReActAgent.builder()
                // ... 其他配置
                .hook(loggingHook)
                .build();
            详细用法请参考 Hook 系统。
            
            状态管理与会话
            解决的问题：智能体的对话历史、配置等状态需要能够保存和恢复，以支持会话持久化。
            
            AgentScope 将对象的”初始化”与”状态”分离：
            
            saveState() - 导出当前状态为可序列化的 Map
            
            loadState() - 从保存的状态恢复
            
            Session 提供跨运行的持久化存储：
            
            // 保存会话
            SessionManager.forSessionId("user123")
                .withSession(new JsonSession(Path.of("sessions")))
                .addComponent(agent)
                .saveSession();
            
            // 恢复会话
            SessionManager.forSessionId("user123")
                .withSession(new JsonSession(Path.of("sessions")))
                .addComponent(agent)
                .loadIfExists();
            响应式编程
            解决的问题：LLM 调用和工具执行通常涉及 I/O 操作，同步阻塞会浪费资源。
            
            AgentScope 基于 Project Reactor 构建，使用：
            
            Mono<T> - 返回 0 或 1 个结果
            
            Flux<T> - 返回 0 到 N 个结果（用于流式）
            
            // 非阻塞调用
            Mono<Msg> responseMono = agent.call(msg);
            
            // 需要结果时阻塞
            Msg response = responseMono.block();
            
            // 或异步处理
            responseMono.subscribe(response ->
                System.out.println(response.getTextContent())
            );
            
            """;
}
