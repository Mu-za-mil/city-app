# Local monitoring and environment configuration

All values injected into containers by `docker-compose.yml` are configured through environment variables. For local development, copy `.env.example` to `.env` and review the settings before starting services.

## First-time setup

1. Copy `.env.example` to `.env`.
2. Replace the JWT, database and Grafana password placeholders with unique local secrets.
3. Keep `.env` out of version control; the repository ignores it.
4. Validate the configuration with `docker compose config --quiet`.
5. Start the desired profile, for example `docker compose --profile monitoring up -d`.

Compose requires the referenced variables to be present. If a variable is missing, configuration fails with an explanatory message rather than silently using an inline value. Optional third-party integration credentials may remain empty when those integrations are not configured.

## Production note

This Compose configuration is for local development. The Kafka replication factor of 1, plaintext listeners and automatic topic creation are local-development settings, not a production Kafka design. For shared or production deployments, use the deployment platform's secret manager, review network exposure and use environment-specific Kafka/security configuration. Do not use `.env.example` placeholder values in a deployed environment.
