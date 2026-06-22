package com.dawn.ai.agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Description;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 统一的 Web 工具，基于 Tavily API 实现搜索和网页内容提取。
 *
 * 两种模式：
 * - search：搜索互联网，返回结果列表（标题、链接、摘要）
 * - extract：提取指定 URL 的网页正文内容
 */
@Slf4j
@Component
@Description("搜索互联网或提取网页内容。适用于最新/current/recent 信息、外部公开事实、官方文档、版本号、发布日期、新闻、价格、状态，以及用户明确要求网上查询或官方来源的问题。mode='search' 搜索网页；mode='extract' 提取指定 URL 正文。基于 Tavily API。")
public class WebTool implements Function<WebTool.Request, WebTool.Response> {

    private static final String SEARCH_URL = "https://api.tavily.com/search";
    private static final String EXTRACT_URL = "https://api.tavily.com/extract";
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 10000;

    @Value("${app.tools.tavily.api-key:${TAVILY_API_KEY:}}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WebTool(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("操作模式：'search'（搜索互联网）或 'extract'（提取指定 URL 的网页内容）")
            String mode,

            @JsonProperty(required = true)
            @JsonPropertyDescription("search 模式下为搜索关键词；extract 模式下为要提取内容的网页 URL")
            String query,

            @JsonProperty(required = false)
            @JsonPropertyDescription("search 模式下的搜索深度：'basic'（快速）或 'advanced'（深度，默认）")
            String searchDepth,

            @JsonProperty(required = false)
            @JsonPropertyDescription("search 模式下返回的最大结果数（默认 5，最大 10）")
            Integer maxResults
    ) {
        public Request(String mode, String query) {
            this(mode, query, null, null);
        }
    }

    public record SearchResult(String title, String url, String content, double score) {}

    public record Response(
            List<SearchResult> results,
            String extractedContent,
            String error
    ) {
        static Response searchOk(List<SearchResult> results) {
            return new Response(results, null, null);
        }

        static Response extractOk(String content) {
            return new Response(null, content, null);
        }

        static Response fail(String error) {
            return new Response(null, null, error);
        }
    }

    @Override
    public Response apply(Request req) {
        if (apiKey == null || apiKey.isBlank()) {
            return Response.fail("Tavily API Key 未配置。请设置环境变量 TAVILY_API_KEY 或配置 app.tools.tavily.api-key。");
        }

        if (req.mode() == null || req.mode().isBlank()) {
            return Response.fail("缺少 mode 参数，请指定 'search' 或 'extract'。");
        }

        if (req.query() == null || req.query().isBlank()) {
            return Response.fail("缺少 query 参数。");
        }

        try {
            return switch (req.mode().toLowerCase()) {
                case "search" -> doSearch(req);
                case "extract" -> doExtract(req);
                default -> Response.fail("未知 mode: " + req.mode() + "。请使用 'search' 或 'extract'。");
            };
        } catch (Exception e) {
            log.error("[WebTool] {} 失败: {}", req.mode(), e.getMessage());
            return Response.fail(req.mode() + " 失败: " + e.getMessage());
        }
    }

    private Response doSearch(Request req) {
        int maxResults = (req.maxResults() != null && req.maxResults() > 0)
                ? Math.min(req.maxResults(), 10)
                : DEFAULT_MAX_RESULTS;
        String depth = (req.searchDepth() != null && !req.searchDepth().isBlank())
                ? req.searchDepth()
                : "advanced";

        Map<String, Object> body = Map.of(
                "query", req.query(),
                "search_depth", depth,
                "max_results", maxResults
        );

        String responseBody = post(SEARCH_URL, body);
        return parseSearchResponse(responseBody);
    }

    private Response doExtract(Request req) {
        Map<String, Object> body = Map.of(
                "urls", List.of(req.query())
        );

        String responseBody = post(EXTRACT_URL, body);
        return parseExtractResponse(responseBody);
    }

    private String post(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(url, entity, String.class);
    }

    private Response parseSearchResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.path("results");
            List<SearchResult> list = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode item : results) {
                    list.add(new SearchResult(
                            item.path("title").asText(""),
                            item.path("url").asText(""),
                            UntrustedContent.wrap(item.path("content").asText("")),
                            item.path("score").asDouble(0)
                    ));
                }
            }
            log.debug("[WebTool] search 返回 {} 条结果", list.size());
            return Response.searchOk(list);
        } catch (Exception e) {
            return Response.fail("解析搜索结果失败: " + e.getMessage());
        }
    }

    private Response parseExtractResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                JsonNode first = results.get(0);
                String title = first.path("title").asText("");
                String rawContent = first.path("raw_content").asText("");

                String content = title.isBlank() ? rawContent : "# " + title + "\n\n" + rawContent;

                if (content.length() > DEFAULT_MAX_CONTENT_LENGTH) {
                    content = content.substring(0, DEFAULT_MAX_CONTENT_LENGTH)
                            + "\n... [内容截断，共 " + content.length() + " 字符]";
                }
                log.debug("[WebTool] extract 返回 {} 字符", content.length());
                return Response.extractOk(UntrustedContent.wrap(content));
            }

            JsonNode failed = root.path("failed_results");
            if (failed.isArray() && !failed.isEmpty()) {
                String failUrl = failed.get(0).path("url").asText("");
                String failError = failed.get(0).path("error").asText("未知错误");
                return Response.fail("提取失败 [" + failUrl + "]: " + failError);
            }

            return Response.fail("未能提取到网页内容");
        } catch (Exception e) {
            return Response.fail("解析提取结果失败: " + e.getMessage());
        }
    }
}
