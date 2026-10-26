# patreon-gateway (patreon-gateway/): its boot jar from `./gradlew -p patreon-gateway bootJar`, on Temurin's Java, with
# no browser: the gateway connects to Playwright's browser server, a container of its own beside it in the pod, from
# Microsoft's Playwright image (k8s/base/deployment-patreon-gateway.yaml), which the node pulls from Microsoft's
# registry, so its gigabyte of browsers never goes through ours. Playwright's own driver (Node) is in the jar.

# Extracting is unzipping, so it runs on the build machine's own platform
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jre AS layers
WORKDIR /layers
COPY patreon-gateway/build/libs/patreon-gateway.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
# No browsers to download; Playwright's driver is unpacked to the temporary directory
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    PATREON_STATE_DIR=/state \
    HOME=/tmp \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0"

WORKDIR /app
# Least to most often changing, so a code-only change pushes just the last layer over the tailnet
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./

# Not root; the same user as the browser's container (pwuser in Playwright's image), so the state volume is theirs alike
USER 1001:1001
EXPOSE 8090/tcp
ENTRYPOINT ["java", "-jar", "application.jar"]
