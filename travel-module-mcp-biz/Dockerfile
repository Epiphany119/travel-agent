FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
COPY travel-mcp-protocol ./travel-mcp-protocol
COPY travel-mcp-client ./travel-mcp-client
COPY travel-mcp-server-weather ./travel-mcp-server-weather
COPY travel-mcp-server-poi ./travel-mcp-server-poi
COPY travel-mcp-server-meal ./travel-mcp-server-meal
COPY travel-mcp-server-budget ./travel-mcp-server-budget
COPY travel-mcp-server-itinerary ./travel-mcp-server-itinerary
ARG MODULE=travel-mcp-server-weather
RUN mvn -pl ${MODULE} -am package -DskipTests -B
FROM eclipse-temurin:21-jre
ARG MODULE=travel-mcp-server-weather
WORKDIR /app
COPY --from=builder /app/${MODULE}/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
