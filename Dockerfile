# Build stage
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Copy full project ( .dockerignore excludes build outputs and IDE files)
COPY . .

# Build the worker distribution (no daemon, skip tests for faster image build)
RUN ./gradlew :olo-worker:installDist --no-daemon --no-build-cache -x test

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the installed distribution from builder
COPY --from=builder /build/olo-worker/build/install/olo-worker/bin bin
COPY --from=builder /build/olo-worker/build/install/olo-worker/lib lib

# Copy pipeline config (worker expects config/ in working dir)
COPY config config

# Ensure the launch script is executable
RUN chmod +x /app/bin/olo-worker

# Default env vars (override at run time)
ENV OLO_QUEUE=olo-chat-queue-oolama
ENV OLO_IS_DEBUG_ENABLED=false
ENV OLO_TENANT_IDS=default
ENV OLO_DEFAULT_TENANT_ID=default

# Worker connects to Temporal server (e.g. host.docker.internal:7233); server is not in this image.
EXPOSE 7233

# Run the worker (same as ./gradlew :olo-worker:run)
ENTRYPOINT ["/app/bin/olo-worker"]
