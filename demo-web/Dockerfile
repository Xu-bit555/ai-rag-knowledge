# Demo Web App Dockerfile
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/demo-web-1.0.0.jar /app/demo-web.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/demo-web.jar"]