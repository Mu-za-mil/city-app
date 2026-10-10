# Local monitoring setup

Grafana is available only when the Compose `monitoring` profile is enabled. Its administrator password must be provided through the local environment and is not stored in the Compose file.

## First-time setup

1. Copy `.env.example` to `.env`.
2. Replace the JWT/database placeholders with unique local secrets.
3. Set `GRAFANA_ADMIN_PASSWORD` to a strong, unique random password. Do not reuse a production credential.
4. Keep `.env` out of version control; the repository ignores it.
5. Start monitoring with `docker compose --profile monitoring up -d`.

If `GRAFANA_ADMIN_PASSWORD` is missing or empty, Compose intentionally fails rather than starting Grafana with a known default password. Compose may require this variable even when another profile is selected because interpolation is evaluated across the Compose model; set it in your local `.env` before running Compose commands.

## Production note

This Compose configuration is for local development. For shared or production deployments, inject the administrator password from the deployment platform's secret manager, rotate credentials through the approved process, restrict Grafana network access, and do not use `.env.example` placeholder values.
