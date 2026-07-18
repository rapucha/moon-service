# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM maven:3.9.16-eclipse-temurin-25-noble AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY pom.xml .
COPY backend/pom.xml backend/pom.xml
COPY prototypes/jvm-scoring/pom.xml prototypes/jvm-scoring/pom.xml

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl backend -am dependency:go-offline

COPY backend/src backend/src
COPY frontend frontend
COPY prototypes/jvm-scoring/src prototypes/jvm-scoring/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl backend -am package -DskipTests

FROM eclipse-temurin:25.0.3_9-jre-noble
WORKDIR /app

ARG MOON_BUILD_REVISION=local
ARG MOON_IMAGE_SOURCE=https://github.com/rapucha/moon-service
ENV MOON_BUILD_REVISION=${MOON_BUILD_REVISION}
LABEL org.opencontainers.image.revision=${MOON_BUILD_REVISION}
LABEL org.opencontainers.image.source=${MOON_IMAGE_SOURCE}

RUN groupadd --gid 10001 moonservice \
    && useradd --uid 10001 --gid moonservice --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin moonservice

COPY --chmod=0555 backend/docker/healthcheck.sh /usr/local/bin/moon-service-healthcheck
COPY --chmod=0444 --from=build /workspace/backend/target/moon-service-backend-*.jar /app/moon-service.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --start-interval=2s --retries=3 \
    CMD ["/usr/local/bin/moon-service-healthcheck"]

STOPSIGNAL SIGTERM
USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/moon-service.jar"]
