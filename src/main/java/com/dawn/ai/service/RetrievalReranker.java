package com.dawn.ai.service;

import org.springframework.ai.document.Document;

import java.util.List;

public interface RetrievalReranker {

    List<Document> rerank(RetrievalRequest request, List<Document> candidates);
}
