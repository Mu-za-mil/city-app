# City App Kubernetes

This directory is the starting point for a staged Kubernetes deployment. It is intentionally not a deployable application stack yet.

## Current stage: configuration foundation

- `base/namespace.yaml` defines the isolated `city-app` namespace.
- `base/kustomization.yaml` is the Kustomize entry point and applies a shared application label.
- CI renders the Kustomize base to catch structural/rendering errors before any cluster deployment.

## Deliberately not included yet

No application Deployments, Services, Ingress, ConfigMaps, or Secrets are created in this stage. The City App services depend on PostgreSQL/PostGIS, Redis, Kafka, and Eureka, and their environment-variable contracts differ. We should model those dependencies and health checks before introducing workloads; otherwise Pods could start without the application being usable.

Never commit real credentials. Later stages should use Kubernetes Secret references for sensitive values and non-secret ConfigMaps for ordinary configuration. `.env.example` values are development placeholders, not production credentials.

## Validation

From the repository root, with `kubectl` installed:

```bash
kubectl kustomize k8s/base
```

This renders the manifests locally and does not contact or modify a cluster. CI performs the same render check. No deployment is performed by this workflow.

## Next stages

1. Agree on local cluster installation (kind is a lightweight option for a resource-constrained laptop).
2. Define the local dependency strategy for PostGIS, Redis, Kafka, and Eureka.
3. Add one workload at a time with explicit configuration, resource requests/limits, probes, and internal Services.
4. Validate rendered manifests and application configuration in CI before any local apply.
