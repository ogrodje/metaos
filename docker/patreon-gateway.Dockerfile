# patreon-gateway (patreon-gateway/): its boot jar from `./gradlew -p patreon-gateway bootJar`, on Playwright's image,
# which has Chromium and what it needs to run; Java comes from Temurin's. Playwright's version here and in
# patreon-gateway/build.gradle.kts have to be the same, as each Playwright drives the browsers of its own version.

FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jre AS layers
WORKDIR /layers
COPY patreon-gateway/build/libs/patreon-gateway.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre AS java

FROM mcr.microsoft.com/playwright:v1.64.0-noble
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    PATREON_STATE_DIR=/state \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40.0"

WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./

# pwuser, Playwright's own non-root user; /state is a volume (k8s/base/deployment-patreon-gateway.yaml)
USER pwuser
EXPOSE 8090/tcp
ENTRYPOINT ["java", "-jar", "application.jar"]
