# Runtime-only image: Maven builds target/app.jar before this image is built.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Run the application as an unprivileged user.
RUN addgroup -S appgroup && adduser -S -D -H -G appuser appuser \
    && mkdir -p /app/logs \
    && chown -R appuser:appuser /app

COPY --chown=appuser:appuser target/app.jar /app/app.jar

USER appuser:appuser

# Documentation only; the application port is configured by Spring Boot.
EXPOSE 8083

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
