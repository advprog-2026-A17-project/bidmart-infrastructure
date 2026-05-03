# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY gradlew gradlew.bat ./
COPY gradle gradle/
COPY build.gradle.kts settings.gradle.kts ./
RUN --mount=type=cache,id=gradle-infra,target=/root/.gradle \
    chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src src/
RUN --mount=type=cache,id=gradle-infra,target=/root/.gradle \
    ./gradlew bootJar -x test --no-daemon

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/build/libs/bidmart-gateway-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8000

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]