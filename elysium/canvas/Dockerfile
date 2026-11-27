# syntax=docker/dockerfile:1
# Elysium's Canvas (infra/elysium/factory/canvas): OpenHands' Agent Canvas with what MetaOS' development takes, as
# devenv.nix has it, so its agents build, check and run MetaOS as a developer does: JDK 25 (Gradle runs through its
# wrapper), yarn 4 (fe/'s yarn.lock is Berry's) on the image's Node 24, just, psql, ffmpeg, terraform and tflint,
# kubectl, helm and kustomize. Not devenv's 1Password, secretspec or gcloud: an agent has no account to use them with.
#
# Built and pushed to the Vultr registry (ams.vultrcr.com/ogrodje) by CI, .github/workflows/elysium.yml, when this file
# changes; `just canvas-image` says its tag, which goes into infra/elysium/factory's canvas_image.
ARG CANVAS=ghcr.io/openhands/agent-canvas:1.46.0

FROM eclipse-temurin:25-jdk AS jdk

# The single binaries, each from its project's own release and checked against the checksums it publishes beside it
FROM debian:trixie-slim AS tools
ARG JUST=1.59.0 KUBECTL=1.37.3 HELM=4.3.2 KUSTOMIZE=5.8.3 TERRAFORM=1.17.2 TFLINT=0.66.0
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl unzip \
 && rm -rf /var/lib/apt/lists/*
SHELL ["/bin/bash", "-euo", "pipefail", "-c"]
WORKDIR /dl
RUN mkdir /out \
 && f=just-$JUST-x86_64-unknown-linux-musl.tar.gz \
 && curl -fsSLO "https://github.com/casey/just/releases/download/$JUST/$f" \
 && curl -fsSL "https://github.com/casey/just/releases/download/$JUST/SHA256SUMS" | grep " $f\$" | sha256sum -c - \
 && tar -xzf "$f" -C /out just
RUN curl -fsSLo kubectl "https://dl.k8s.io/release/v$KUBECTL/bin/linux/amd64/kubectl" \
 && echo "$(curl -fsSL "https://dl.k8s.io/release/v$KUBECTL/bin/linux/amd64/kubectl.sha256")  kubectl" | sha256sum -c - \
 && install -m 0755 kubectl /out/
RUN f=helm-v$HELM-linux-amd64.tar.gz \
 && curl -fsSLO "https://get.helm.sh/$f" \
 && curl -fsSL "https://get.helm.sh/$f.sha256sum" | sha256sum -c - \
 && tar -xzf "$f" linux-amd64/helm && install -m 0755 linux-amd64/helm /out/
RUN f=kustomize_v${KUSTOMIZE}_linux_amd64.tar.gz \
 && curl -fsSLO "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv$KUSTOMIZE/$f" \
 && curl -fsSL "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv$KUSTOMIZE/checksums.txt" \
    | grep " $f\$" | sha256sum -c - \
 && tar -xzf "$f" -C /out kustomize
RUN f=terraform_${TERRAFORM}_linux_amd64.zip \
 && curl -fsSLO "https://releases.hashicorp.com/terraform/$TERRAFORM/$f" \
 && curl -fsSL "https://releases.hashicorp.com/terraform/$TERRAFORM/terraform_${TERRAFORM}_SHA256SUMS" \
    | grep " $f\$" | sha256sum -c - \
 && unzip -q "$f" terraform -d /out
RUN f=tflint_linux_amd64.zip \
 && curl -fsSLO "https://github.com/terraform-linters/tflint/releases/download/v$TFLINT/$f" \
 && curl -fsSL "https://github.com/terraform-linters/tflint/releases/download/v$TFLINT/checksums.txt" \
    | grep " $f\$" | sha256sum -c - \
 && unzip -q "$f" tflint -d /out

FROM ${CANVAS}
LABEL org.opencontainers.image.source="https://github.com/ogrodje/metaos" \
      org.opencontainers.image.description="Elysium's Agent Canvas, with MetaOS' development tools"
USER root

RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-client ffmpeg unzip \
 && rm -rf /var/lib/apt/lists/*

COPY --from=tools /out/ /usr/local/bin/

# JAVA_HOME for Gradle's wrapper; java and the rest on the PATH even in a login shell, whose /etc/profile sets its own
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
RUN for b in /opt/java/openjdk/bin/*; do [ -e "/usr/local/bin/${b##*/}" ] || ln -s "$b" /usr/local/bin/; done

# yarn 4, devenv's, for every user: corepack's shims, and Yarn itself fetched now into a home they all read
ENV COREPACK_HOME=/opt/corepack COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable && corepack install -g yarn@4.18.1 && chmod -R a+rX /opt/corepack

# The image's user, openhands
USER 10001
