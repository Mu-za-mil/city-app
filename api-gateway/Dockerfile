# Dockerfile
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the fat JAR (built by Maven)
COPY target/*.jar app.jar

# Create non-root user (security best practice)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE 8080

# JVM tuning for containers
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-XX:+ExitOnOutOfMemoryError", \
            "-jar", "app.jar"]