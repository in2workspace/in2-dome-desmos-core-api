# Runtime-only image for node B in the two-node replication tests. Mirrors stage 2 of
# the root Dockerfile exactly (same base image, same non-root user, same entrypoint) --
# the only difference is that the jar arrives pre-built rather than from a Gradle build
# inside this image. Deliberately NOT `ImageFromDockerfile` on the root Dockerfile
# itself: its stage 1 runs `gradle build` WITH tests unless SKIP_TESTS=true, which would
# start this very test suite -- and its own Testcontainers topology -- recursively inside
# a container with no Docker socket.
FROM bellsoft/liberica-openjdk-alpine-musl:17
RUN addgroup -S nonroot \
    && adduser -S nonroot -G nonroot
USER nonroot
WORKDIR /app
COPY desmos-api.jar /app/desmos-api.jar
ENTRYPOINT ["java", "-jar", "/app/desmos-api.jar"]
