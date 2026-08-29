# RAG knowledge base

Reviews are grounded in your team's context. The engine chunks, embeds and retrieves team knowledge so agents can cite specs, runbooks and history instead of guessing.

## Pipeline

```
team docs (specs / runbooks / videos)
  → chunked
  → embedded (via model gateway)
  → stored in pgvector
  → retrieved per review → injected into agent context
```

## Retrieval

- **Hybrid** — vector similarity plus BM25 keyword matching
- **Reranking** — a reranker refines the shortlist before injection
- **Gating** — a relevance gate decides whether retrieved context is used at all

## Configuration

```yaml
review:
  rag:
    enabled: true
    chunk-size: 512
    top-k: 4
```

## What it improves

- Security reviews can cite the team's own security policy
- Architecture reviews can cite the layering conventions document
- Similar historical findings become retrievable context for new reviews

## Where it stores data

Embeddings live in PostgreSQL with pgvector. If the embedding dimension ever mismatches the stored index, the vector store rebuilds automatically — a documented behavior of the LangChain4j integration.
