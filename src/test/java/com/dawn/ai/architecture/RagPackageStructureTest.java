package com.dawn.ai.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagPackageStructureTest {

    @Test
    @DisplayName("RAG 相关类应按功能拆分到 rag 子包")
    void ragClassesShouldLiveInFeaturePackages() {
        assertThatCode(() -> Class.forName("com.dawn.ai.rag.RagService")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.rag.query.QueryRewriter")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.rag.ingestion.OverlapTextSplitter")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.rag.retrieval.RetrievalRouter")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.rag.evaluation.RetrievalEvaluator")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("新增的 RAG 类不应继续停留在 service 包")
    void newRagClassesShouldNotRemainInServicePackage() {
        assertThatThrownBy(() -> Class.forName("com.dawn.ai.service.PostgresBm25Retriever"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.dawn.ai.service.RetrievalEvaluator"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
