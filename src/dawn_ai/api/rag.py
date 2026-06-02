"""RAG API"""

from typing import Optional
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from ..rag import RagService

router = APIRouter()
rag_service = RagService()


class IngestRequest(BaseModel):
    """文档摄取请求"""
    content: str
    source: str
    category: Optional[str] = ""


class IngestResponse(BaseModel):
    """文档摄取响应"""
    id: str
    source: str
    category: str


class SearchResult(BaseModel):
    """搜索结果"""
    id: str
    content: str
    source: str
    category: str
    score: float


@router.post("/rag/ingest", response_model=IngestResponse)
async def ingest(request: IngestRequest):
    """摄取文档"""
    try:
        result = await rag_service.ingest(
            content=request.content,
            source=request.source,
            category=request.category,
        )
        return IngestResponse(**result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/rag/search")
async def search(query: str, top_k: int = 3):
    """搜索知识库"""
    try:
        results = await rag_service.search(query, top_k)
        return [SearchResult(**r) for r in results]
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
