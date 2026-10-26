# The MetaOS app: the boot jar from `./gradlew bootJar`, split into Spring Boot's layers, on distroless.
# The jar is built outside (just push, .github/workflows/deploy.yml) - it is platform independent, so building the
# linux/amd64 image on an Apple silicon machine is a copy, not an emulated Gradle build.

# Extracting is unzipping, so it runs on the build machine's own platform
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jre AS layers
WORKDIR /layers
COPY build/libs/metaos.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM gcr.io/distroless/java25-debian13:nonroot
# ffprobe, which reads what's in an episode's video and audio (MediaProbe.kt): a static build, as distroless has no
# libraries to link to
COPY --from=mwader/static-ffmpeg:9.0.2 /ffprobe /usr/local/bin/ffprobe
WORKDIR /app
# New Relic's Java agent and newrelic.yml, from `./gradlew bootJar` (build/newrelic): it reports the app's requests,
# errors and logs once NEW_RELIC_LICENSE_KEY is set, and doesn't start without it
COPY build/newrelic/ ./newrelic/
# Least to most often changing, so a code-only change pushes just the last layer over the tailnet
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./

# 8080: the app and /mcp; 9090: actuator, when MANAGEMENT_SERVER_PORT=9090 (k8s/base/deployment-metaos.yaml)
EXPOSE 8080/tcp 9090/tcp
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
# Conscrypt (Cloud Storage's TLS) loads its native library, which a future Java blocks without native access
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-javaagent:/app/newrelic/newrelic.jar", "-jar", "application.jar"]
