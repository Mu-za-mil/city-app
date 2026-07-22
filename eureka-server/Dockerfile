# Dockerfile
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the fat JAR (built by Maven)
COPY target/*.jar app.jar

RUN apk add --no-cache curl

# Create non-root user (security best practice)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

RUN mkdir -p /app/logs && chown -R appuser:appgroup /app/logs

USER appuser

EXPOSE 8080

# JVM tuning for containers:
# -XX:+UseContainerSupport: JVM reads container memory limits (not host)
# -XX:MaxRAMPercentage=75.0: Use 75% of container RAM for JVM heap
# -XX:+ExitOnOutOfMemoryError: crash-fast instead of limping along
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-XX:+ExitOnOutOfMemoryError", \
            "-jar", "app.jar"]