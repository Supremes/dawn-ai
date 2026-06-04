package com.dawn.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.stringtemplate.v4.ST;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized prompt template manager backed by StringTemplate 4.
 *
 * Loads all {@code .st} files from {@code classpath:prompts/} at startup.
 * Each file is a raw template using {@code $} delimiters for parameters.
 */
@Slf4j
@Component
public class PromptManager {

    private static final String PROMPTS_LOCATION = "classpath:prompts/*.st";
    private static final char DELIMITER = '$';
    private static final Pattern ATTR_PATTERN = Pattern.compile("\\$([^$]+)\\$");

    private final ResourcePatternResolver resourceResolver;
    private final Map<String, String> rawTemplates = new ConcurrentHashMap<>();

    public PromptManager(ResourcePatternResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    @PostConstruct
    void loadTemplates() {
        try {
            Resource[] resources = resourceResolver.getResources(PROMPTS_LOCATION);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) continue;
                String name = filename.replace(".st", "");
                try {
                    String content = resource.getContentAsString(StandardCharsets.UTF_8);
                    validateTemplate(name, content);
                    rawTemplates.put(name, content);
                    log.info("[PromptManager] Loaded template: {}", name);
                } catch (Exception e) {
                    log.error("[PromptManager] Failed to load template: {}", name, e);
                }
            }
            log.info("[PromptManager] Loaded {} template(s): {}", rawTemplates.size(), rawTemplates.keySet());
        } catch (IOException e) {
            log.error("[PromptManager] Failed to scan prompts directory", e);
        }
    }

    /**
     * Render a template with parameters.
     *
     * @param name   template name (filename without .st extension)
     * @param params template parameters
     * @return rendered string
     * @throws IllegalArgumentException if template not found
     */
    public String render(String name, Map<String, Object> params) {
        String raw = getRaw(name);
        ST st = new ST(raw, DELIMITER, DELIMITER);
        params.forEach(st::add);
        return st.render();
    }

    /**
     * Render a template without parameters.
     *
     * @param name template name (filename without .st extension)
     * @return rendered string
     * @throws IllegalArgumentException if template not found
     */
    public String render(String name) {
        String raw = getRaw(name);
        ST st = new ST(raw, DELIMITER, DELIMITER);
        return st.render();
    }

    private String getRaw(String name) {
        String raw = rawTemplates.get(name);
        if (raw == null) {
            throw new IllegalArgumentException("Prompt template not found: " + name
                    + ". Available: " + rawTemplates.keySet());
        }
        return raw;
    }

    private void validateTemplate(String name, String content) {
        Matcher matcher = ATTR_PATTERN.matcher(content);
        while (matcher.find()) {
            String attr = matcher.group(1).trim();
            // ST4 attributes must be simple identifiers (no spaces, no special chars)
            if (!attr.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                log.warn("[PromptManager] Template '{}' contains suspicious attribute: '${}$'", name, attr);
            }
        }
    }
}
