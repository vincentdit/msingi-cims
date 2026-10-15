# Development stand-in (never deployed to production)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 mock
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
USER mock
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
