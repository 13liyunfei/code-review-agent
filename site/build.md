# Build & test

## Requirements

- JDK 17, Maven
- PostgreSQL 17 + pgvector, Redis, Gitea (for a full local stack — use `start-all.sh`)

## Commands

```bash
./mvnw -o compile            # compile offline
./mvnw -o test               # full suite (141 tests, 33 classes, no infra required)
```

## Test coverage highlights

- **State machine** — 440 transition combinations across 11 test classes
- **Planning** — DAG topo-order, cycle rejection, failure propagation
- **Tool calling** — decision loop semantics, path-traversal rejection
- **Agent capabilities** — reflection store, precision/recall assertions, extension registry ordering

See `docs/test-report-*.md` for the detailed reports.

## IDE setup

If IntelliJ shows red errors everywhere, run `./mvnw -o compile` once to warm the classpath — a known first-open quirk with multi-module Spring Boot projects.

## Releasing

This is an application repository (not a library), so no Maven Central release — releases are tagged git versions. Its reusable components live in the standalone `agent-kit` repository, published to Maven Central as `io.github.13liyunfei:agent-kit:0.1.0`.
