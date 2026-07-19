FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/mymobi-whatsapp.jar app.jar
EXPOSE 3000
ENTRYPOINT ["java", "-jar", "app.jar"]
