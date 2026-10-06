# ---- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- run (non-root)
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 cims
WORKDIR /app
COPY --from=build /src/target/cims-backend-*.jar app.jar
USER cims
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
