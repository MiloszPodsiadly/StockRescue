FROM container-registry.oracle.com/java/openjdk:27.0.1-oraclelinux9@sha256:69fdaaf1d4d388c1617055ae8417b203df3fae27058c4a7054479ca254d46819 AS build
WORKDIR /workspace
COPY backend/ backend/
COPY contracts/ contracts/
COPY infrastructure/backend/Healthcheck.java /tmp/Healthcheck.java
RUN --mount=type=cache,target=/root/.gradle bash backend/gradlew -p backend --no-daemon bootJar && javac -d /tmp/healthcheck /tmp/Healthcheck.java

FROM container-registry.oracle.com/java/openjdk:27.0.1-oraclelinux9@sha256:69fdaaf1d4d388c1617055ae8417b203df3fae27058c4a7054479ca254d46819
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/backend/build/libs/stockrescue.jar /app/stockrescue.jar
COPY --from=build --chown=10001:10001 /tmp/healthcheck /app/healthcheck
COPY LICENSE /app/LICENSE
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/stockrescue.jar"]
