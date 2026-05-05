package com.dawn.ai.rag.ingestion;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Token-aware splitter with configurable overlap and punctuation-aware boundaries.
 */
@Component
public class OverlapTextSplitter implements DocumentTransformer {
    private static final int DEFAULT_MIN_CHUNK_SIZE_CHARS = 350;
    private static final int DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED = 5;
    private static final int DEFAULT_MAX_NUM_CHUNKS = 10_000;
    private static final List<Character> DEFAULT_PUNCTUATION_MARKS = List.of('.', '?', '!', '\n');

    private final Encoding encoding;
    private final int chunkSize;
    private final int chunkOverlap;
    private final int minChunkSizeChars;
    private final int minChunkLengthToEmbed;
    private final int maxNumChunks;
    private final List<Character> punctuationMarks;

    @Autowired
    public OverlapTextSplitter(@Value("${app.ai.rag.chunk-size:500}") int chunkSize,
                               @Value("${app.ai.rag.chunk-overlap:50}") int chunkOverlap) {
        this(chunkSize,
                chunkOverlap,
                DEFAULT_MIN_CHUNK_SIZE_CHARS,
                DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED,
                DEFAULT_MAX_NUM_CHUNKS,
                DEFAULT_PUNCTUATION_MARKS);
    }

    private OverlapTextSplitter(int chunkSize,
                        int chunkOverlap,
                        int minChunkSizeChars,
                        int minChunkLengthToEmbed,
                        int maxNumChunks,
                        List<Character> punctuationMarks) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be >= 0 and < chunkSize");
        }
        if (minChunkSizeChars < 0) {
            throw new IllegalArgumentException("minChunkSizeChars must be >= 0");
        }
        if (minChunkLengthToEmbed < 0) {
            throw new IllegalArgumentException("minChunkLengthToEmbed must be >= 0");
        }
        if (maxNumChunks <= 0) {
            throw new IllegalArgumentException("maxNumChunks must be positive");
        }
        if (punctuationMarks == null || punctuationMarks.isEmpty()) {
            throw new IllegalArgumentException("punctuationMarks must not be empty");
        }

        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.minChunkSizeChars = minChunkSizeChars;
        this.minChunkLengthToEmbed = minChunkLengthToEmbed;
        this.maxNumChunks = maxNumChunks;
        this.punctuationMarks = List.copyOf(punctuationMarks);
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> transformed = new ArrayList<>();
        for (Document document : documents) {
            transformed.addAll(split(document));
        }
        return transformed;
    }

    private List<Document> split(Document document) {
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return List.of(document);
        }

        String normalizedText = text.trim();
        IntArrayList tokens = encoding.encode(normalizedText);
        if (tokens.size() <= chunkSize) {
            return List.of(createChunk(document, normalizedText, 0, 1));
        }

        List<String> chunkTexts = new ArrayList<>();
        int start = 0;
        while (start < tokens.size() && chunkTexts.size() < maxNumChunks) {
            int end = Math.min(start + chunkSize, tokens.size());
            String chunkText = decode(tokens, start, end);
            String normalizedChunk = normalizeChunk(chunkText, tokens.size() > chunkSize);

            if (normalizedChunk.length() > minChunkLengthToEmbed) {
                chunkTexts.add(normalizedChunk);
            }

            if (end >= tokens.size()) {
                break;
            }

            int consumedTokens = countConsumedTokens(chunkText, normalizedChunk);
            start += Math.max(consumedTokens - chunkOverlap, 1);
        }

        List<Document> chunks = new ArrayList<>(chunkTexts.size());
        for (int index = 0; index < chunkTexts.size(); index++) {
            chunks.add(createChunk(document, chunkTexts.get(index), index, chunkTexts.size()));
        }
        return chunks;
    }

    private Document createChunk(Document document, String text, int chunkIndex, int chunkCount) {
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        metadata.put("parentDocumentId", document.getId());
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("chunkCount", chunkCount);

        String chunkId = createChunkId(document.getId(), chunkIndex, chunkCount);
        Document chunk = new Document(chunkId, text, metadata);
        chunk.setContentFormatter(document.getContentFormatter());
        return chunk;
    }

    private String createChunkId(String documentId, int chunkIndex, int chunkCount) {
        if (chunkCount == 1) {
            return documentId;
        }
        return UUID.nameUUIDFromBytes((documentId + "#chunk-" + chunkIndex).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private String decode(IntArrayList tokens, int startInclusive, int endExclusive) {
        IntArrayList window = new IntArrayList(endExclusive - startInclusive);
        for (int index = startInclusive; index < endExclusive; index++) {
            window.add(tokens.get(index));
        }
        return encoding.decode(window);
    }

    private String normalizeChunk(String chunkText, boolean allowPunctuationBoundary) {
        String candidate = chunkText;
        if (allowPunctuationBoundary) {
            int punctuationIndex = getLastPunctuationIndex(candidate);
            if (punctuationIndex > minChunkSizeChars) {
                candidate = candidate.substring(0, punctuationIndex + 1);
            }
        }
        return candidate.trim();
    }

    private int countConsumedTokens(String rawChunkText, String normalizedChunk) {
        if (normalizedChunk.isBlank()) {
            return encoding.encode(rawChunkText).size();
        }

        int consumedTokens = encoding.encode(normalizedChunk).size();
        if (consumedTokens == 0) {
            consumedTokens = encoding.encode(rawChunkText.trim()).size();
        }
        return Math.max(consumedTokens, 1);
    }

    private int getLastPunctuationIndex(String chunkText) {
        int lastPunctuationIndex = -1;
        for (Character punctuationMark : punctuationMarks) {
            lastPunctuationIndex = Math.max(lastPunctuationIndex, chunkText.lastIndexOf(punctuationMark));
        }
        return lastPunctuationIndex;
    }
}
