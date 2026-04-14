#!/bin/bash
BASE_URL="http://localhost:8080/api/v1/rag"

declare -a DOCS=(
'{"content":"Spring AI is a framework that brings the power of AI to Spring Boot applications. It supports multiple AI providers including OpenAI, Azure OpenAI, and local models via Ollama or Infinity.","source":"spring-ai-docs","category":"framework"}'
'{"content":"BGE-M3 is a multilingual embedding model developed by BAAI. It supports over 100 languages and produces 1024-dimensional vectors. It excels at cross-lingual semantic search tasks.","source":"model-docs","category":"embedding"}'
'{"content":"Retrieval-Augmented Generation (RAG) combines retrieval systems with generative models. Documents are embedded into a vector store, and relevant chunks are retrieved at query time to ground model responses.","source":"rag-tutorial","category":"architecture"}'
'{"content":"PGVector is a PostgreSQL extension that enables storage and similarity search over high-dimensional vectors. It supports HNSW and IVFFlat indexes with cosine, L2, and inner product distance metrics.","source":"pgvector-docs","category":"database"}'
'{"content":"Hybrid search merges dense vector retrieval with sparse BM25 keyword search using Reciprocal Rank Fusion (RRF). This improves recall for queries where exact keyword matching matters alongside semantic similarity.","source":"rag-tutorial","category":"retrieval"}'
'{"content":"Cross-encoder reranking re-scores initial retrieval candidates using a full attention model that jointly encodes the query and each document. It provides higher precision than bi-encoder retrieval alone.","source":"model-docs","category":"reranking"}'
'{"content":"Docker Compose allows you to define and run multi-container applications. Services can declare health checks and dependencies to ensure proper startup ordering.","source":"devops-notes","category":"infrastructure"}'
'{"content":"NVIDIA Container Toolkit enables GPU access inside Docker containers. After installing nvidia-ctk and configuring the Docker daemon runtime, containers can use --gpus all flag.","source":"devops-notes","category":"infrastructure"}'
'{"content":"Redis is an in-memory data structure store used as cache, message broker, and session store. Spring Boot auto-configures Redis via Spring Data Redis when redis host is provided.","source":"spring-ai-docs","category":"infrastructure"}'
'{"content":"Prometheus collects metrics by scraping HTTP endpoints. Spring Boot Actuator exposes a /actuator/prometheus endpoint that Prometheus can scrape to monitor JVM, HTTP, and custom application metrics.","source":"devops-notes","category":"monitoring"}'
)

echo "=== Batch Ingest (10 documents) ==="
for i in "${!DOCS[@]}"; do
  resp=$(curl -s -X POST "$BASE_URL/ingest" \
    -H "Content-Type: application/json" \
    -d "${DOCS[$i]}")
  docId=$(echo "$resp" | python3 -c "import json,sys; r=json.load(sys.stdin); print(r.get('docId','ERR'))")
  status=$(echo "$resp" | python3 -c "import json,sys; r=json.load(sys.stdin); print(r.get('status','ERR'))")
  echo "  [$(( i+1 ))/10] docId=$docId  status=$status"
done
echo ""
