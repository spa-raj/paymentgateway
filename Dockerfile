# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper files first for layer caching
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Make Maven wrapper executable and download dependencies
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code and build
COPY src src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Install wget for health checks, unzip for Flyway init container
RUN apk add --no-cache wget unzip

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring

# Copy JAR from builder
COPY --from=builder /app/target/paymentgateway-0.0.1-SNAPSHOT.jar app.jar

# Change ownership to spring user
RUN chown spring:spring app.jar

# Switch to non-root user
USER spring:spring

# Expose paymentgateway port
EXPOSE 8084

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8084/actuator/health || exit 1

# JVM options for container environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Entrypoint
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
