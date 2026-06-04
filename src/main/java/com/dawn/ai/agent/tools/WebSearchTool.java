package com.dawn.ai.agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 网页搜索工具。
 *
 * 支持多种搜索引擎 API（通过配置切换）：
 * - SerpAPI（Google Search）
 * - Bing Search API
 * - 自定义 API
 *
 * 配置项：
 * app.tools.web-search.api-url    — 搜索 API 端点
 * app.tools.web-search.api-key    — API 密钥
 * app.tools.web-search.provider   — 提供商（serpapi / bing / custom）
 * app.tools.web-search.max-results — 最大结果数（默认 5）
 */
@Slf4j
@Component
@Description("在互联网上搜索信息。输入搜索关键词，返回搜索结果（标题、链接、摘要）。适用于查找最新信息、技术文档、新闻等。")
public class WebSearchTool implements Function<WebSearchTool.Request, WebSearchTool.Response> {

    @Value("${app.tools.web-search.api-url:}")
    private String apiUrl;

    @Value("${app.tools.web-search.api-key:}")
    private String apiKey;

    @Value("${app.tools.web-search.provider:serpapi}")
    private String provider;

    @Value("${app.tools.web-search.max-results:5}")
    private int maxResults;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WebSearchTool(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("搜索关键词或问题")
            String query,

            @JsonProperty(required = false)
            @JsonPropertyDescription("搜索结果数量限制（默认 5）")
            Integer count
    ) {
        public Request(String query) {
            this(query, null);
        }
    }

    public record SearchResult(String title, String url, String snippet) {}
    public record Response(List<SearchResult> results, String error) {}

    @Override
    public Response apply(Request req) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return new Response(List.of(), "网页搜索未配置。请设置 app.tools.web-search.api-url 和 app.tools.web-search.api-key。");
        }

        int count = (req.count() != null && req.count() > 0) ? Math.min(req.count(), 10) : maxResults;

        try {
            return switch (provider.toLowerCase()) {
                case "serpapi" -> searchWithSerpApi(req.query(), count);
                case "bing" -> searchWithBing(req.query(), count);
                default -> searchWithCustom(req.query(), count);
            };
        } catch (Exception e) {
            log.error("[WebSearchTool] Search failed for '{}': {}", req.query(), e.getMessage());
            return new Response(List.of(), "搜索失败: " + e.getMessage());
        }
    }

    private Response searchWithSerpApi(String query, int count) {
        String url = apiUrl + "?q={query}&num={count}&api_key={apiKey}&engine=google";
        String body = restTemplate.getForObject(url, String.class, query, count, apiKey);
        return parseSerpApiResponse(body);
    }

    private Response searchWithBing(String query, int count) {
        String url = apiUrl + "?q={query}&count={count}";
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Ocp-Apim-Subscription-Key", apiKey);
        var entity = new org.springframework.http.HttpEntity<>(headers);

        var resp = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class, query, count);
        return parseBingResponse(resp.getBody());
    }

    private Response searchWithCustom(String query, int count) {
        String url = apiUrl + "?q={query}&count={count}";
        String body = restTemplate.getForObject(url, String.class, query, count);
        return parseGenericResponse(body);
    }

    private Response parseSerpApiResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.path("organic_results");
            List<SearchResult> list = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode item : results) {
                    list.add(new SearchResult(
                            item.path("title").asText(""),
                            item.path("link").asText(""),
                            item.path("snippet").asText("")
                    ));
                }
            }
            return new Response(list, null);
        } catch (Exception e) {
            return new Response(List.of(), "解析搜索结果失败: " + e.getMessage());
        }
    }

    private Response parseBingResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.path("webPages").path("value");
            List<SearchResult> list = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode item : results) {
                    list.add(new SearchResult(
                            item.path("name").asText(""),
                            item.path("url").asText(""),
                            item.path("snippet").asText("")
                    ));
                }
            }
            return new Response(list, null);
        } catch (Exception e) {
            return new Response(List.of(), "解析搜索结果失败: " + e.getMessage());
        }
    }

    private Response parseGenericResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            // 尝试通用格式：results 数组，每个元素有 title/url/snippet
            JsonNode results = root.path("results");
            if (!results.isArray()) results = root.path("items");
            List<SearchResult> list = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode item : results) {
                    list.add(new SearchResult(
                            item.path("title").asText(""),
                            item.path("url").asText(item.path("link").asText("")),
                            item.path("snippet").asText(item.path("description").asText(""))
                    ));
                }
            }
            return new Response(list, null);
        } catch (Exception e) {
            return new Response(List.of(), "解析搜索结果失败: " + e.getMessage());
        }
    }
}
