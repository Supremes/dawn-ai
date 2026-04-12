package com.dawn.ai.service;

import org.springframework.ai.document.Document;

import java.util.List;

public interface SparseRetriever {

    List<Document> retrieve(RetrievalRequest request, int limit);
}
