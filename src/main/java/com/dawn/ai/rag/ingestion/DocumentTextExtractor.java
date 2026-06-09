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

    /** Default JSON field whose value is used as the document body for each JSONL line. */
    public static final String DEFAULT_JSONL_TEXT_FIELD = "text";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String extract(MultipartFile file, DocumentType documentType) {
        try (InputStream inputStream = file.getInputStream()) {
            String text = switch (documentType) {
                case TEXT -> new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                case PDF, WORD, EXCEL -> extractWithTika(inputStream, file);
                // JSONL is record-oriented (one document per line); it must be ingested via
                // extractJsonlRecords(), not as a single concatenated string.
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
     * Extract one document body per line from a JSONL (newline-delimited JSON) file.
     *
     * <p>Each non-blank line is parsed as a JSON object and the value of {@code textField}
     * is taken as that record's body. Blank lines, malformed lines, and lines missing a
     * usable text field are skipped with a warning so a few dirty rows don't abort the
     * whole dataset. If no usable record is found at all, an exception is raised.
     *
     * @param file      the uploaded JSONL file
     * @param textField the JSON field holding the body; falls back to {@link #DEFAULT_JSONL_TEXT_FIELD}
     * @return the body text of every valid record, in file order
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
