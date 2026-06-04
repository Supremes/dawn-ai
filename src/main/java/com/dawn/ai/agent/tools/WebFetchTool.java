package com.dawn.ai.agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Description;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 抓取指定 URL 的网页内容并转换为纯文本。
 *
 * 使用 Apache Tika（项目已有依赖）将 HTML 转为纯文本，
 * 保留核心内容、去除导航/广告等噪音。
 *
 * 安全约束：
 * - 仅允许 HTTP/HTTPS 协议
 * - 响应体大小限制（默认 500KB）
 * - 超时控制
 */
@Slf4j
@Component
@Description("抓取指定 URL 的网页内容并转换为纯文本。输入网页 URL，返回网页的文本内容。适用于读取网页文章、API 文档、在线资源等。")
public class WebFetchTool implements Function<WebFetchTool.Request, WebFetchTool.Response> {

    private static final long DEFAULT_MAX_BYTES = 500 * 1024; // 500KB
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://.+");

    @Value("${app.tools.web-fetch.max-bytes:" + DEFAULT_MAX_BYTES + "}")
    private long maxBytes;

    @Value("${app.tools.web-fetch.timeout-seconds:15}")
    private int timeoutSeconds;

    private final RestTemplate restTemplate;
    private final Tika tika;

    public WebFetchTool(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
        this.tika = new Tika();
    }

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("要抓取的网页 URL（必须以 http:// 或 https:// 开头）")
            String url,

            @JsonProperty(required = false)
            @JsonPropertyDescription("是否提取纯文本（默认 true）。设为 false 则返回原始 HTML。")
            Boolean extractText,

            @JsonProperty(required = false)
            @JsonPropertyDescription("最大返回字符数（默认 10000）")
            Integer maxLength
    ) {
        public Request(String url) {
            this(url, null, null);
        }
    }

    public record Response(String content, String contentType, int contentLength, String error) {}

    @Override
    public Response apply(Request req) {
        if (req.url() == null || !URL_PATTERN.matcher(req.url()).matches()) {
            return new Response(null, null, 0, "无效的 URL。必须以 http:// 或 https:// 开头。");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "DawnAI/1.0 (AI Agent Web Fetcher)");
            headers.set("Accept", "text/html,application/xhtml+xml,*/*");

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    req.url(), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                return new Response(null, null, 0, "HTTP 请求失败: " + response.getStatusCode());
            }

            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                return new Response(null, null, 0, "网页内容为空");
            }

            if (body.length > maxBytes) {
                return new Response(null, null, 0,
                        String.format("网页内容过大 (%d bytes)，超过限制 (%d bytes)", body.length, maxBytes));
            }

            String rawContent = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            String contentType = response.getHeaders().getContentType() != null
                    ? response.getHeaders().getContentType().toString()
                    : "text/html";

            boolean extractText = req.extractText() == null || req.extractText();
            String content;

            if (extractText) {
                content = tika.parseToString(new java.io.ByteArrayInputStream(body));
            } else {
                content = rawContent;
            }

            int maxLen = (req.maxLength() != null && req.maxLength() > 0) ? req.maxLength() : 10000;
            if (content.length() > maxLen) {
                content = content.substring(0, maxLen) + "\n... [内容截断，共 " + content.length() + " 字符]";
            }

            log.debug("[WebFetchTool] Fetched {} chars from {}", content.length(), req.url());
            return new Response(content, contentType, content.length(), null);

        } catch (Exception e) {
            log.error("[WebFetchTool] Failed to fetch {}: {}", req.url(), e.getMessage());
            return new Response(null, null, 0, "抓取失败: " + e.getMessage());
        }
    }
}
