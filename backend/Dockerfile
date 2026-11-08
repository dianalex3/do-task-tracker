FROM gradle:8.13-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/install/do-backend/ ./
EXPOSE 8080
ENTRYPOINT ["./bin/do-backend"]
