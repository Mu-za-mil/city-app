# City App Kubernetes dependency inventory

This is a planning document, not a deployment manifest. It records the current Docker Compose configuration as the starting point for Kubernetes work. Confirm each item against the service's Spring configuration and runtime behavior before adding workloads.

## Current application services

| Workload | Container port | Current Compose configuration / dependencies |
|---|---:|---|
| Eureka server | 8761 | Spring profile; service discovery for the other services |
| API Gateway | 8000 | Eureka host, Redis host, JWT secret, Spring profile, logging configuration |
| Auth service | 8081 | Eureka host, PostgreSQL URL/password, Redis host, Kafka brokers, JWT secret, Spring profile |
| Notification service | 8083 | Eureka host, PostgreSQL URL/password, Redis host, Kafka brokers, Mail host, JWT secret, Spring profile; Firebase credentials path is optional in Compose |
| Main backend | 8080 | Eureka host, PostgreSQL URL/password, Redis host, Kafka bootstrap servers, Mail host, JWT secret, auth-service URL, Spring profile, logging configuration; Razorpay values are optional in Compose |

Ports are taken from the current Dockerfiles and should be verified against the application's effective Spring configuration before defining Kubernetes Services and probes.

## External dependencies to plan

| Dependency | Compose reference | Kubernetes decision still required |
|---|---|---|
| PostgreSQL with PostGIS | postgis/postgis:16-3.4, service postgres | For the first local exercise, decide whether to run it inside the local cluster or keep it in Docker Compose and connect through a reachable host endpoint. Do not assume localhost from a Pod means the developer machine. |
| Redis | redis:7-alpine, service redis | Choose in-cluster or Compose-managed, and define the endpoint reachable from Pods. |
| Kafka + Zookeeper | Compose services kafka and zookeeper | Choose in-cluster or Compose-managed. Kafka advertised listeners must be reachable from clients running inside Kubernetes; copying the Compose kafka:9092 value without matching DNS/networking will not work automatically. |
| Eureka | application service eureka-server | Keep service discovery configuration consistent with Kubernetes DNS and the current Spring profile. Confirm whether the apps actually need Eureka when running behind Kubernetes Services. |
| MailHog | Compose service mailhog | Optional local-only mail sink if notification flows need to be exercised. Do not treat it as a production mail provider. |
| Payment / Firebase integrations | Compose environment variables / optional credentials path | Use Kubernetes Secret references or an approved external secret mechanism for credentials. Never commit live credentials or .env files. |

## Configuration rules for future manifests

- Use immutable GHCR image tags tied to a verified commit SHA. Do not use latest for the initial deployment exercise.
- Put non-sensitive configuration in a ConfigMap and sensitive values in Secret references. Keep secret values out of Git; manifests may reference Secret names and keys.
- The existing .env.example values are placeholders for local Compose and are not valid production credentials.
- Keep the first rollout small. Add one workload and its Service at a time, with explicit resource requests/limits and probes only after the correct health endpoint and startup behavior are verified.
- Do not assume Docker Compose depends_on behavior exists in Kubernetes. Applications must tolerate dependency startup delays and retry safely.
- A rendered manifest check proves Kustomize can render the configuration; it does not prove a cluster accepts it or that dependencies are reachable.

## Proposed rollout sequence

1. Validate the Kustomize foundation in CI.
2. Install and verify one lightweight local cluster.
3. Decide how the cluster reaches PostgreSQL/PostGIS, Redis, and Kafka.
4. Add Eureka and validate its readiness and DNS/service discovery behavior.
5. Add one application service at a time, beginning with the simplest safe service and checking logs, probes, and dependency connectivity.
6. Add Gateway exposure only after internal service communication is proven.
