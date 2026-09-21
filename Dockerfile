# OneCase MCP Server Dockerfile
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
COPY demo-web ./demo-web
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/ai-rag-knowledge-2.0.jar /app/onecase.jar

ENV ARTIFACT_STORAGE_DIR=/onecase-artifacts
VOLUME ["/onecase-artifacts"]

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/onecase.jar"]