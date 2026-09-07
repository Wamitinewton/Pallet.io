# syntax=docker/dockerfile:1
# Self-contained build of config-server from the reactor root.
# For everyday image builds prefer Cloud Native Buildpacks:
#   ./mvnw -pl services/config-server -am spring-boot:build-image
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY platform-common/ platform-common/
COPY services/ services/
COPY config-repo/ config-repo/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -pl services/config-server -am package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --system --uid 1001 pallet
USER pallet
COPY --from=build /workspace/services/config-server/target/config-server-*.jar app.jar
COPY --from=build /workspace/config-repo/ config-repo/
EXPOSE 8888
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
