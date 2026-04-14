package com.dawn.ai.rag.retrieval.rerank;

import com.dawn.ai.rag.retrieval.RetrievalRequest;
import org.springframework.ai.document.Document;

import java.util.List;

public interface RetrievalReranker {

    List<Document> rerank(RetrievalRequest request, List<Document> candidates);
}
