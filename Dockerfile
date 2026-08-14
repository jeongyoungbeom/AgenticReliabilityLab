FROM node:22-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend ./
RUN npm run build

FROM gradle:9.5.1-jdk21 AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies
COPY src src
COPY --from=frontend-build /frontend/dist src/main/resources/static
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
RUN addgroup --system arl && adduser --system --ingroup arl arl
WORKDIR /app
COPY --from=build /workspace/build/libs/AgenticReliabilityLab-0.0.1-SNAPSHOT.jar app.jar
USER arl
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
