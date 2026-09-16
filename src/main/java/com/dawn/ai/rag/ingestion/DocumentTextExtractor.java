package com.dawn.ai.rag.ingestion;

import com.dawn.ai.rag.constants.DocumentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class DocumentTextExtractor {

    /** 默认的 JSON 字段名，JSONL 每一行取这个字段的值作为文档正文。 */
    public static final String DEFAULT_JSONL_TEXT_FIELD = "text";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String extract(MultipartFile file, DocumentType documentType) {
        try (InputStream inputStream = file.getInputStream()) {
            String text = switch (documentType) {
                case TEXT -> new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                case PDF, WORD, EXCEL -> extractWithTika(inputStream, file);
                // JSONL 是按记录组织的（一行一个文档），必须走 extractJsonlRecords()
                // 逐条导入，不能拼成一个大字符串处理。
                case JSONL -> throw new IllegalArgumentException(
                        "JSONL files must be ingested per-record; use extractJsonlRecords()");
            };

            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("No textual content could be extracted from file");
            }
            return text;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read uploaded file", e);
        }
    }

    /**
     * 从 JSONL（按行分隔的 JSON）文件里逐行提取文档正文，一行一条。
     *
     * <p>每个非空行都按 JSON 对象解析，取 {@code textField} 字段的值作为这条记录的正文。
     * 空行、格式错误的行、缺少可用文本字段的行都会跳过并打一条 warning，
     * 这样几条脏数据不会把整个数据集搞挂。如果一条可用记录都没有，就抛异常。
     *
     * @param file      上传的 JSONL 文件
     * @param textField 存放正文的 JSON 字段；没传就 fallback 到 {@link #DEFAULT_JSONL_TEXT_FIELD}
     * @return 所有有效记录的正文，按文件里的顺序排列
     */
    public List<String> extractJsonlRecords(MultipartFile file, String textField) {
        String field = (textField != null && !textField.isBlank()) ? textField : DEFAULT_JSONL_TEXT_FIELD;
        List<String> records = new ArrayList<>();
        int lineNumber = 0;
        int skipped = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonNode node = objectMapper.readTree(line);
                    JsonNode value = node.get(field);
                    if (value == null || value.isNull() || value.asText().isBlank()) {
                        skipped++;
                        log.warn("[DocumentTextExtractor] JSONL line {} has no usable '{}' field, skipping", lineNumber, field);
                        continue;
                    }
                    records.add(value.asText());
                } catch (IOException e) {
                    skipped++;
                    log.warn("[DocumentTextExtractor] Failed to parse JSONL line {}, skipping", lineNumber, e);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read uploaded file", e);
        }

        if (records.isEmpty()) {
            throw new IllegalArgumentException("No valid JSONL records with field '" + field + "' could be extracted from file");
        }
        log.info("[DocumentTextExtractor] Extracted {} JSONL record(s) using field '{}' ({} line(s) skipped)",
                records.size(), field, skipped);
        return records;
    }

    private String extractWithTika(InputStream inputStream, MultipartFile file) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            if (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getOriginalFilename());
            }
            ParseContext parseContext = new ParseContext();
            parser.parse(inputStream, handler, metadata, parseContext);
            return handler.toString();
        } catch (Exception e) {
            log.warn("[DocumentTextExtractor] Failed to parse file: {}", file.getOriginalFilename(), e);
            throw new IllegalArgumentException("Unsupported or corrupted document content", e);
        }
    }
}
