FROM container-registry.oracle.com/java/openjdk:27.0.1.0.3-oraclelinux9@sha256:ec8568d488454d0087e88a38b818abc46098ab8701843e9449db3468a641a326 AS build
WORKDIR /workspace
COPY backend/ backend/
COPY contracts/ contracts/
COPY infrastructure/backend/Healthcheck.java /tmp/Healthcheck.java
RUN --mount=type=cache,target=/root/.gradle test "$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.runtime.version = //p')" = "27.0.1.0.3+1-2" \
    && java --version \
    && bash backend/gradlew -p backend --no-daemon bootJar \
    && javac -d /tmp/healthcheck /tmp/Healthcheck.java \
    && mkdir -p /tmp/stockrescue-app \
    && cd /tmp/stockrescue-app \
    && jar xf /workspace/backend/build/libs/stockrescue.jar \
    && modules="$(jdeps -q --ignore-missing-deps --recursive --multi-release 27 --print-module-deps --class-path 'BOOT-INF/lib/*' BOOT-INF/classes BOOT-INF/lib/*.jar)" \
    && case ",$modules," in *,jdk.crypto.ec,*) ;; *) modules="$modules,jdk.crypto.ec" ;; esac \
    && jlink --add-modules "$modules" --no-header-files --no-man-pages --compress=zip-6 --output /opt/java-runtime

FROM docker.io/library/oraclelinux:9-slim@sha256:38f3f1f40cd8f28d020f38d69fa30f8a9fe9f7e661dff78592f85d110ec4696d
WORKDIR /app
COPY --from=build /opt/java-runtime /opt/java-runtime
COPY --from=build --chown=10001:10001 /workspace/backend/build/libs/stockrescue.jar /app/stockrescue.jar
COPY --from=build --chown=10001:10001 /tmp/healthcheck /app/healthcheck
COPY LICENSE /app/LICENSE
RUN mkdir -p /tmp && chmod 1777 /tmp
USER 10001:10001
ENV JAVA_HOME=/opt/java-runtime PATH="/opt/java-runtime/bin:$PATH" JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.io.tmpdir=/tmp" SERVER_ADDRESS=0.0.0.0
RUN test "$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.runtime.version = //p')" = "27.0.1.0.3+1-2" \
    && java --version \
    && java --list-modules | cut -d@ -f1 | grep -Fxq java.sql \
    && java --list-modules | cut -d@ -f1 | grep -Fxq java.xml \
    && java --list-modules | cut -d@ -f1 | grep -Fxq jdk.crypto.ec
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/app/stockrescue.jar"]
