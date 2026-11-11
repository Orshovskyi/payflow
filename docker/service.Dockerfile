# One image definition for every PayFlow service. Build the jars first (./mvnw package), then:
#   docker build -f docker/service.Dockerfile --build-arg MODULE=wallet-service -t payflow/wallet-service .
# The jar is split into Spring Boot layers so that a code change rebuilds only the small application layer.

FROM eclipse-temurin:21-jre AS extract
ARG MODULE
WORKDIR /build
COPY ${MODULE}/target/${MODULE}-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre
RUN groupadd --system payflow && useradd --system --gid payflow payflow
USER payflow
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
# Heap follows the container memory limit, so every strategy runs with exactly the same resources.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
