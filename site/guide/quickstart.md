# Quick start

## One-click full stack (recommended)

Two scripts in the repo root bring up everything — PostgreSQL 17 + pgvector, Redis, Gitea, the engine and the console:

```bash
./start-all.sh    # start the whole stack
./stop-all.sh     # stop it all again
```

After the first boot:

```bash
./start-all.sh
# open a PR in Gitea → the engine reviews it automatically
./stop-all.sh
```

## Manual startup

### 1. Build

```bash
./mvnw -o compile
```

### 2. Configure

Point `application.yml` (or env vars) at your infrastructure:

- PostgreSQL 17 + pgvector for memory and persistence
- Redis for queues
- Gitea / GitLab base URL and token
- TokenHub API key for the model gateway

### 3. Run

```bash
./mvnw -o spring-boot:run -Dspring-boot.run.arguments="--gitea.base-url=http://localhost:3000 --gitea.api-token=<token> --server.port=8080"
```

Then register the webhook in your SCM so PR events reach the engine. See [SCM integration](./integration) for the exact steps.

## Verify it works

```bash
curl localhost:8080/health
```

When the webhook fires, the engine logs the review pipeline — agents started, findings found, report written back — and the PR page in Gitea shows the inline comments.
