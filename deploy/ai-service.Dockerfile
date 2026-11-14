# Input is the verified, repackaged production jar, never target/test-classes.
FROM eclipse-temurin:17-jre
ARG VERSION=development
ARG REVISION=unknown
LABEL org.opencontainers.image.version=$VERSION org.opencontainers.image.revision=$REVISION
WORKDIR /app
COPY --chown=10001:10001 ai-service/target/ai-service-0.1.0-SNAPSHOT.jar /app/ai-service.jar
USER 10001:10001
EXPOSE 8086 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-XX:ActiveProcessorCount=2", "-jar", "/app/ai-service.jar"]
