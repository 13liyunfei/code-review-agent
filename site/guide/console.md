# Management console

A Vue 3 web console (`code-review-console`, port 8081) fronts the engine with team management, skills, knowledge and review operations.

## Skills market

Curated skill packs (rule sets, agent prompts, knowledge templates) can be enabled per team. Skills are versioned YAML bundles — the same format the engine consumes natively.

## Team knowledge base

Specs, runbooks and videos uploaded per team are chunked and indexed into the RAG store, so reviews ground themselves in your team's documentation.

## Custom agents

Team admins define business-specific review agents — name, system prompt, rules, model — through the console. They join the review pipeline as parallel reviewers. Every prompt passes an injection pre-check before it is stored.

## Review operations

- Browse and replay review trajectories
- Monitor quality trends (feedback, rejection, rework)
- Manage workflow items (reassign, exception, spot-check)

## Architecture

The console is a thin proxy: it calls the engine's admin API and serves the Vue build from its own static resources. Frontend changes ship as `npm run build` output into the Spring Boot static directory.
