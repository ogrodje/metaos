# Keycloak for MetaOS: an optimized build with MetaOS's own provider (keycloak/remember-me). compose.yml runs its dev
# stage, so both have the same Keycloak. Realm metaos is Terraform's (infra/keycloak), not imported from a file.
ARG KEYCLOAK=quay.io/keycloak/keycloak:26.8.1

# The provider, compiled against the Keycloak it goes in, as Keycloak's image has no JDK (its Java is 21). Jars run
# anywhere, so it's built on the build's own platform. No manifest (which names the JDK's build) and a fixed date: the
# same source makes the same jar, so `kc.sh build` below still comes from the cache (deploy.yml).
FROM --platform=$BUILDPLATFORM ${KEYCLOAK} AS keycloak
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS provider
COPY --from=keycloak /opt/keycloak/lib/lib/main /keycloak
COPY keycloak/remember-me /src
RUN cd /src && javac --release 21 -cp '/keycloak/*' -d /out $(find . -name '*.java' | sort) \
 && cp -r META-INF /out/ \
 && cd /out && jar --create --no-manifest --date=2026-01-01T00:00:00Z --file /metaos-remember-me.jar $(find . -type f | sort)

# compose.yml's: `start-dev` builds Keycloak as it starts
FROM ${KEYCLOAK} AS dev
COPY --from=provider /metaos-remember-me.jar /opt/keycloak/providers/

FROM ${KEYCLOAK} AS builder
# Build-time options only; hostname, database and credentials are runtime env (k8s/base/deployment-keycloak.yaml)
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
# Sign-ins, and why they failed, counted (keycloak_user_events_total), which Prometheus passes on to New Relic
ENV KC_EVENT_METRICS_USER_ENABLED=true
COPY --from=provider /metaos-remember-me.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM ${KEYCLOAK}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
