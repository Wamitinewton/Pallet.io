# syntax=docker/dockerfile:1
# config-server is a fully independent Maven project (own pom.xml, own mvnw) - this build never
# reaches into platform-common/ or any other service. config-repo/ is the one exception: it's
# shared local-dev config, same status as docker-compose.yml at the repo root, so the build
# context is still the repo root:
#
#   DOCKER_BUILDKIT=1 docker build -f services/config-server/Dockerfile \
#     --secret id=github_token,env=GITHUB_TOKEN \
#     -t pallet/config-server .
#
# GITHUB_TOKEN needs at least read:packages, to pull platform-common-observability from GitHub
# Packages (see PACKAGES.md) - passed as a BuildKit secret, never baked into an image layer.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY services/config-server/.mvn/ .mvn/
COPY services/config-server/mvnw services/config-server/pom.xml ./
COPY services/config-server/src/ src/
COPY config-repo/ config-repo/
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=github_token,env=GITHUB_TOKEN \
    mkdir -p /root/.m2 && \
    printf '<settings><servers><server><id>pallet-github</id><username>x-access-token</username><password>%s</password></server></servers></settings>\n' "$GITHUB_TOKEN" > /root/.m2/settings.xml && \
    ./mvnw -B package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --system --uid 1001 pallet
USER pallet
COPY --from=build /workspace/target/config-server-*.jar app.jar
COPY --from=build /workspace/config-repo/ config-repo/
EXPOSE 8888
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
