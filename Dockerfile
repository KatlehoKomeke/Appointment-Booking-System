# syntax=docker/dockerfile:1
#
# Dockerfile: builds BranchBook into one image. The React app is compiled and packaged inside the Spring Boot jar,
# and the jar runs on a Java runtime as an unprivileged user. `make up` builds it through docker compose, CI builds
# it on every push, and the hosted demo runs the same image.
#
# Stages:
#   web      compiles web/ with Node 24 into static files.
#   api      packages api/ with the Maven wrapper, with the web build inside, then unpacks the jar into layers.
#   runtime  the image that ships: a Java 25 runtime and the application, with no compiler, source or build tool.
#
# Tests do not run here. CI runs them on every push, and the integration tests need Docker, which a build lacks.
# The build stages run on the builder's own platform, because JavaScript and Java bytecode are the same everywhere;
# only the runtime stage is built for the target platform (amd64 or arm64).

FROM --platform=$BUILDPLATFORM node:26.12.0-alpine AS web
WORKDIR /src/web
# Dependencies first, so this layer is rebuilt only when the lockfile changes.
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:25.0.4.1_1-jdk-alpine AS api
WORKDIR /src/api
COPY api/.mvn/ .mvn/
COPY api/mvnw api/pom.xml ./
COPY api/src/ src/
# Spring Boot serves anything under static/ in the jar; the app's index.html becomes the page at /.
COPY --from=web /src/web/dist/ src/main/resources/static/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package \
 && java -Djarmode=tools -jar target/branchbook.jar extract --layers --destination /extracted

FROM eclipse-temurin:25.0.4.1_1-jre-alpine AS runtime
LABEL org.opencontainers.image.title="BranchBook" \
      org.opencontainers.image.description="Branch appointment booking for the fictional Fynbos Bank" \
      org.opencontainers.image.source="https://github.com/KatlehoKomeke/Appointment-Booking-System" \
      org.opencontainers.image.licenses="MIT"
# A fixed, unprivileged user. The application files stay owned by root, so the process cannot modify its own code.
RUN addgroup -S -g 10001 branchbook \
 && adduser -S -D -H -u 10001 -G branchbook -s /sbin/nologin branchbook
WORKDIR /app
# Least-changing layers first: a code change rebuilds only the last, small layer.
COPY --from=api /extracted/dependencies/ ./
COPY --from=api /extracted/spring-boot-loader/ ./
COPY --from=api /extracted/snapshot-dependencies/ ./
COPY --from=api /extracted/application/ ./
USER 10001:10001
# Times are stored and logged in UTC; the app converts to South African time itself (docs/architecture.md, section 8).
ENV TZ=UTC
EXPOSE 8080
# Healthy once the app accepts traffic and PostgreSQL answers. docker compose waits for it in `make up`.
HEALTHCHECK --interval=5s --timeout=3s --start-period=90s --retries=3 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${PORT:-8080}/readyz" || exit 1
# The heap may use 75% of the container's memory limit. On running out of memory the JVM exits, so the container is
# restarted cleanly instead of limping on. Exec form: Java is PID 1 and receives SIGTERM for a graceful shutdown.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "branchbook.jar"]
