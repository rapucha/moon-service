# syntax=docker/dockerfile:1.7

FROM maven:3.9.16-eclipse-temurin-25-noble AS build
WORKDIR /workspace

COPY pom.xml .
COPY backend/pom.xml backend/pom.xml
COPY prototypes/jvm-scoring/pom.xml prototypes/jvm-scoring/pom.xml

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl backend -am dependency:go-offline

COPY backend/src backend/src
COPY prototypes/jvm-scoring/src prototypes/jvm-scoring/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl backend -am package -DskipTests

FROM eclipse-temurin:25.0.3_9-jre-noble
WORKDIR /app

COPY --from=build /workspace/backend/target/moon-service-backend-*.jar /app/moon-service.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/moon-service.jar"]
