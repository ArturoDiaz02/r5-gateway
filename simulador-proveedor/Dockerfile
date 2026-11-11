FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /source
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /source/target/provider-simulator-0.1.0-SNAPSHOT.jar simulator.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/simulator.jar"]
