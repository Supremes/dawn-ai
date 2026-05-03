package com.dawn.ai.rag.ingestion;

import com.dawn.ai.rag.constants.DocumentType;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class DocumentTextExtractor {

    public String extract(MultipartFile file, DocumentType documentType) {
        try (InputStream inputStream = file.getInputStream()) {
            String text = switch (documentType) {
                case TEXT -> new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                case PDF, WORD, EXCEL -> extractWithTika(inputStream, file);
            };

            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("No textual content could be extracted from file");
            }
            return text;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read uploaded file", e);
        }
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
        } catch (IOException | SAXException | TikaException e) {
            log.warn("[DocumentTextExtractor] Failed to parse file: {}", file.getOriginalFilename(), e);
            throw new IllegalArgumentException("Unsupported or corrupted document content", e);
        }
    }
}
