FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /source
COPY backend ./backend
RUN mvn -f backend/pom.xml -pl Interface -am -DskipTests package

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /source/backend/Interface/target/agent-interface-0.1.0-SNAPSHOT.jar ./agent-interface.jar
