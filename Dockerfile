# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx256m -Dorg.gradle.workers.max=1 -Dkotlin.compiler.execution.strategy=in-process"

COPY gradlew gradlew.bat ./
COPY gradle gradle/
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --max-workers=1 || true

COPY src src/
RUN ./gradlew bootJar -x test --no-daemon --max-workers=1

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/build/libs/bidmart-gateway-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8000

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
