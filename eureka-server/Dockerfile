# Runtime-only image: Maven builds target/app.jar before this image is built.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# curl is retained for the existing Docker Compose Eureka health check.
# Create an unprivileged runtime identity and prepare the application directory.
RUN addgroup -S appgroup \
    && adduser -S -D -H -G appgroup appuser \
    && apk add --no-cache curl \\\n    && mkdir -p /app/logs \
    && chown -R appuser:appgroup /app

COPY --chown=appuser:appgroup target/app.jar /app/app.jar

USER appuser:appgroup

# Documentation only; Spring Boot configures the actual listening port.
EXPOSE 8761

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
