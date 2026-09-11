FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY services/identity-service/.mvn/ .mvn/
COPY services/identity-service/mvnw services/identity-service/pom.xml ./
COPY services/identity-service/src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=github_token,env=GITHUB_TOKEN \
    mkdir -p /root/.m2 && \
    printf '<settings><servers><server><id>pallet-github</id><username>x-access-token</username><password>%s</password></server></servers></settings>\n' "$GITHUB_TOKEN" > /root/.m2/settings.xml && \
    ./mvnw -B package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --system --uid 1001 pallet
USER pallet
COPY --from=build /workspace/target/identity-service-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
