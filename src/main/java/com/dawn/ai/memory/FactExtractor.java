package com.dawn.ai.memory;

import com.dawn.ai.memory.event.FactsExtractedEvent;
import com.dawn.ai.memory.event.SummarizationRequestEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FactExtractor {

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${app.memory.extraction.enabled:true}")
    private boolean extractionEnabled;

    private static final String FACT_EXTRACTION_PROMPT = """
            你是一个个人信息整理专家，专门从对话中准确提取事实、用户记忆和偏好。
            你的主要角色是从对话中提取相关信息并组织成独立的、可管理的事实。

            需要记住的信息类型：
            1. 个人偏好：跟踪各种类别的喜好、厌恶和特定偏好
            2. 重要个人信息：记住重要的个人信息，如姓名、关系和重要日期
            3. 计划和意图：注意即将到来的事件、旅行、目标和用户分享的任何计划
            4. 活动和服务偏好：回忆餐饮、旅行、爱好和其他服务的偏好
            5. 健康和保健偏好：记录饮食限制、健身习惯和其他保健相关信息
            6. 专业细节：记住职位、工作习惯、职业目标和其他专业信息
            7. 其他信息：跟踪用户分享的最喜欢的书籍、电影、品牌和其他杂项细节

            重要规则：
            - 仅基于用户消息生成事实，不要包含助手或系统消息的信息
            - 检测用户输入的语言，用相同语言记录事实
            - 如果没有找到相关信息，返回空列表

            今天是 %s。

            以下是用户和助手之间的对话。请从中提取相关事实和偏好，以JSON格式返回：
            {"facts": ["事实1", "事实2", ...]}

            对话历史：
            %s
            """;

    @EventListener
    @Async
    public void onSummarizationRequest(SummarizationRequestEvent event) {
        if (!extractionEnabled) {
            return;
        }

        String historyText = event.messages().stream()
                .map(m -> m.getOrDefault("role", "") + ": " + m.getOrDefault("content", ""))
                .collect(Collectors.joining("\n"));

        String prompt = FACT_EXTRACTION_PROMPT.formatted(LocalDate.now(), historyText);

        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            List<String> facts = parseFacts(response);
            if (!facts.isEmpty()) {
                eventPublisher.publishEvent(new FactsExtractedEvent(event.sessionId(), event.userId(), facts));
                log.info("[FactExtractor] Extracted {} facts for session={}", facts.size(), event.sessionId());
            } else {
                log.debug("[FactExtractor] No facts extracted for session={}", event.sessionId());
            }
        } catch (Exception e) {
            log.warn("[FactExtractor] LLM extraction failed for session={}: {}", event.sessionId(), e.getMessage());
        }
    }

    private List<String> parseFacts(String response) {
        try {
            String json = response.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            Map<String, List<String>> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            List<String> facts = parsed.getOrDefault("facts", List.of());
            return facts.stream().filter(f -> f != null && !f.isBlank()).toList();
        } catch (Exception e) {
            log.debug("[FactExtractor] Failed to parse facts JSON: {}", e.getMessage());
            return List.of();
        }
    }
}
