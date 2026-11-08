# Official Temurin manifest-list digests inspected 2026-11-08. Refresh deliberately and revalidate.
FROM eclipse-temurin:17-jdk-jammy@sha256:057a0daec60e87c96571bf5c9c7792065bcb19e804d584cd37d4144038a35717 AS build
WORKDIR /source
COPY . .
RUN ./gradlew clean :app:classes :app:bootJar :clients:healthProbeJar --no-daemon

FROM eclipse-temurin:17-jre-jammy@sha256:2a9eb226abb654c9d1f91b53cf638d20cb3abb726d1b18a67909c29515545469
RUN groupadd --gid 10001 kaflow && useradd --uid 10001 --gid 10001 --no-create-home kaflow \
    && mkdir -p /opt/kaflow /var/lib/kaflow && chown 10001:10001 /var/lib/kaflow
COPY --from=build /source/app/build/libs/app.jar /opt/kaflow/app.jar
COPY --from=build /source/clients/build/libs/healthcheck.jar /opt/kaflow/healthcheck.jar
USER 10001:10001
WORKDIR /opt/kaflow
EXPOSE 8080 9090
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=6 \
    CMD ["java", "-cp", "/opt/kaflow/healthcheck.jar", "kaflow.clients.HealthProbe", "http://127.0.0.1:8080/actuator/health/readiness"]
ENTRYPOINT ["java", "-Xms64m", "-Xmx512m", "-jar", "/opt/kaflow/app.jar"]
CMD ["--kaflow.durable.enabled=true", "--kaflow.durable.directory=/var/lib/kaflow/data", "--server.address=0.0.0.0", "--grpc.server.address=0.0.0.0", "--kaflow.durable.topics[0].name=orders", "--kaflow.durable.topics[0].partitions=1", "--kaflow.durable.shutdown-timeout=10s"]
