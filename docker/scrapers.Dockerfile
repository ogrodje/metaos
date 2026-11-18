# scrapers (scrapers/): its boot jar from `./gradlew -p scrapers bootJar`, on distroless Java as the app's
# (docker/Dockerfile) and patreon-gateway's, whose layers it shares, with no browser: the few sites that need one
# (Eventbrite, GZS) are read in Playwright's browser server, a container of its own beside it in the pod
# (k8s/base/deployment-scrapers.yaml), from Microsoft's image, so its gigabyte of browsers never goes through our
# registry. Playwright's own driver is in the jar, Linux's only (scrapers/build.gradle.kts).

# Extracting is unzipping, so it runs on the build machine's own platform
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jre AS layers
WORKDIR /layers
COPY scrapers/build/libs/scrapers.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM gcr.io/distroless/java25-debian13:nonroot
# No browsers to download; Playwright's driver is unpacked to the temporary directory
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    HOME=/tmp \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0"

WORKDIR /app
# New Relic's Java agent, as the app's (docker/Dockerfile): off without NEW_RELIC_LICENSE_KEY
COPY scrapers/build/newrelic/ ./newrelic/
ENV NEW_RELIC_APP_NAME=scrapers
# Least to most often changing, so a code-only change pushes just the last layer over the tailnet
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./

USER 1001:1001
EXPOSE 8091/tcp
# Without class data sharing, which the agent's addition to the boot classpath has it warn about (docker/Dockerfile)
ENTRYPOINT ["java", "-Xshare:off", "-javaagent:/app/newrelic/newrelic.jar", "-jar", "application.jar"]
