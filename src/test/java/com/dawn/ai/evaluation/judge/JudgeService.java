package com.dawn.ai.evaluation.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class JudgeService {

    private static final Logger log = LoggerFactory.getLogger(JudgeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel judgeChatModel;

    public JudgeService(
            @Value("${JUDGE_BASE_URL:${spring.ai.openai.chat.base-url:${spring.ai.openai.base-url:}}}") String baseUrl,
            @Value("${JUDGE_API_KEY:${spring.ai.openai.chat.api-key:${spring.ai.openai.api-key:}}}") String apiKey,
            @Value("${JUDGE_MODEL:${spring.ai.openai.chat.options.model}}") String model,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry) {

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.0)
                .build();

        RetryTemplate retryTemplate = RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(1000, 2.0, 10000)
                .build();

        this.judgeChatModel = new OpenAiChatModel(api, options, toolCallingManager, retryTemplate, observationRegistry);
        log.info("[JudgeService] initialized with baseUrl={}, model={}", baseUrl, model);
    }

    public JudgeResult judge(JudgeDimension dimension, Map<String, String> variables) {
        String template = loadPromptTemplate(dimension.promptPath());
        String prompt = fillTemplate(template, variables);

        log.info("[Judge] dimension={} | calling judge model", dimension.id());

        List<Message> messages = List.of(
                new SystemMessage("You are an evaluation judge. Follow the instructions exactly. Return only valid JSON."),
                new UserMessage(prompt)
        );

        ChatResponse response = judgeChatModel.call(new Prompt(messages));
        String content = response.getResult().getOutput().getText();

        log.debug("[Judge] dimension={} | raw response: {}", dimension.id(), content);

        return parseResponse(dimension, content);
    }

    private String loadPromptTemplate(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Judge prompt not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load judge prompt: " + path, e);
        }
    }

    private String fillTemplate(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    private JudgeResult parseResponse(JudgeDimension dimension, String content) {
        try {
            String json = extractJson(content);
            JsonNode root = MAPPER.readTree(json);

            double score = root.path("score").asDouble();
            String reasoning = root.path("reasoning").asText("");

            return new JudgeResult(dimension, score, reasoning);
        } catch (Exception e) {
            log.warn("[Judge] dimension={} | failed to parse response, treating as 0: {}", dimension.id(), e.getMessage());
            return new JudgeResult(dimension, 0.0, "Parse error: " + content);
        }
    }

    private String extractJson(String content) {
        String trimmed = content.trim();
        // Strip markdown code fences if present
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
