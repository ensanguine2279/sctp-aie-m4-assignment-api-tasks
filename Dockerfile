# ---------- Build stage ----------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Dependency definition first, so this layer is cached until pom.xml changes.
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline

COPY src ./src
# Tests run in the CI stage, not in the image build. They generate their own
# throwaway RSA keys, so no key material is needed or copied here.
RUN ./mvnw clean package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring

# Only the built JAR is copied. No key is ever part of the image: the public
# key is mounted at runtime (/run/secrets/jwt_public_key under Compose).
COPY --from=build /app/target/*.jar app.jar

USER spring:spring

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]