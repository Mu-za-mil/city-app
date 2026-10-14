#!/usr/bin/env bash
set -euo pipefail

# The CI workflow starts the stack on this Compose project network.
network="${SMOKE_NETWORK:-cityapp-ci_cityapp-network}"

if ! docker run --rm --network "$network" curlimages/curl:8.12.1 sh -ec '
  for target in \
    "Eureka|http://eureka-server:8761/actuator/health" \
    "API Gateway|http://api-gateway:8000/actuator/health" \
    "Auth Service|http://auth-service:8081/actuator/health" \
    "Notification Service|http://notification-service:8083/actuator/health" \
    "Main Backend|http://main-service:8080/actuator/health"
  do
    name="${target%%|*}"
    url="${target#*|}"
    echo "Waiting for ${name} health endpoint..."
    attempt=1
    healthy=0

    while [ "$attempt" -le 60 ]; do
      body="$(curl --silent --fail "$url" 2>/dev/null || true)"
      if printf "%s" "$body" | grep -Eq "\"status\"[[:space:]]*:[[:space:]]*\"UP\""; then
        echo "PASS: ${name} is healthy"
        healthy=1
        break
      fi
      sleep 5
      attempt=$((attempt + 1))
    done

    if [ "$healthy" -ne 1 ]; then
      echo "FAIL: ${name} did not report UP at ${url}"
      echo "Health response:"
      curl --silent --show-error "$url" || true
      if [ "$name" = "Main Backend" ]; then
        echo
        echo "Readiness health response:"
        curl --silent --show-error "http://main-service:8080/actuator/health/readiness" || true
        echo
        echo "Liveness health response:"
        curl --silent --show-error "http://main-service:8080/actuator/health/liveness" || true
      fi
      exit 1
    fi
  done
'; then
  echo "::group::Main Backend container logs (last 300 lines)"
  docker logs --tail 300 cityapp-main || true
  echo "::endgroup::"
  exit 1
fi
