# ---- Build stage ----
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q

COPY src src/
RUN ./gradlew bootJar --no-daemon -q

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

RUN addgroup --system fileshare && adduser --system --ingroup fileshare fileshare
USER fileshare

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
