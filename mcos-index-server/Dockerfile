# mcos-index-server — public marketplace index + publisher review pipeline
# (12-index-server.md). Pure JVM, zero third-party runtime dependencies.
#
# Build from the REPOSITORY ROOT (the Gradle settings graph must be visible):
#   docker build -f mcos-index-server/Dockerfile -t mcos-index-server .
# No Android SDK is needed: :mcos-index-server is a pure-JVM module, the same
# property the CI JVM shards rely on.

FROM gradle:8.10-jdk17 AS build
WORKDIR /home/gradle/src
# Small build hosts (e.g. a 2GB CI/colima VM) OOM-kill the default Gradle
# heap: cap it, and keep the Kotlin compiler in-process so no second daemon
# competes for the same budget. Only :mcos-index-server and its four JVM
# dependencies compile — the full tree is never built.
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1400m -XX:MaxMetaspaceSize=384m"
# Copy the whole tree (build outputs and VCS/worktree noise excluded via
# .dockerignore): Gradle configures every settings.gradle.kts project, so a
# partial checkout would break the configuration phase.
COPY . .
RUN gradle :mcos-index-server:installDist --no-daemon --console=plain \
    -Pkotlin.compiler.execution.strategy=in-process

FROM eclipse-temurin:17-jre
# curl for the container HEALTHCHECK; nothing else beyond the JRE.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root: the registry data dir is a mounted volume, never baked in.
RUN useradd --system --create-home --home-dir /var/lib/mcos-index mcos
WORKDIR /var/lib/mcos-index
COPY --from=build /home/gradle/src/mcos-index-server/build/install/mcos-index-server/ /opt/mcos-index-server/
USER mcos

EXPOSE 8877
# The server binds 127.0.0.1 by default (loopback behind a reverse proxy);
# inside a container it must bind all interfaces — the proxy in front still
# terminates TLS (§8.1). The admin token comes from the environment: the
# process REFUSES to start without one (anonymous management is never
# enabled), which doubles as a config-drift canary.
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8877/v1/health || exit 1
ENTRYPOINT ["/opt/mcos-index-server/bin/mcos-index-server", \
    "--bind-host", "0.0.0.0", "--data-dir", "/var/lib/mcos-index"]
