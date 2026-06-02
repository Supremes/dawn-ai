"""向量检索优化器"""

from typing import List, Dict
import numpy as np
from ..config import get_settings


class VectorOptimizer:
    """向量检索优化器"""

    def __init__(self):
        self.dimension = get_settings().embedding_dimensions

    def normalize_vector(self, vector: List[float]) -> List[float]:
        """归一化向量"""
        norm = np.linalg.norm(vector)
        if norm == 0:
            return vector
        return (np.array(vector) / norm).tolist()

    def batch_normalize(self, vectors: List[List[float]]) -> List[List[float]]:
        """批量归一化"""
        return [self.normalize_vector(v) for v in vectors]

    def compute_similarity(self, vec1: List[float], vec2: List[float]) -> float:
        """计算余弦相似度"""
        vec1 = np.array(vec1)
        vec2 = np.array(vec2)
        return np.dot(vec1, vec2) / (np.linalg.norm(vec1) * np.linalg.norm(vec2))

    def batch_similarity(self, query: List[float], vectors: List[List[float]]) -> List[float]:
        """批量计算相似度"""
        query = np.array(query)
        vectors = np.array(vectors)
        return np.dot(vectors, query).tolist()

    def create_index_sql(self) -> str:
        """创建向量索引的SQL"""
        return """
        CREATE INDEX IF NOT EXISTS documents_embedding_idx
        ON documents
        USING ivfflat (embedding vector_cosine_ops)
        WITH (lists = 100);
        """

    def analyze_index_sql(self) -> str:
        """分析索引使用情况的SQL"""
        return """
        SELECT
            schemaname,
            tablename,
            indexname,
            idx_scan,
            idx_tup_read,
            idx_tup_fetch
        FROM pg_stat_user_indexes
        WHERE tablename = 'documents';
        """
