# ── build stage: compile the jar with Maven ──────────────────────────────
# Nothing from this stage ends up in the final image — it exists only to
# produce backend/target/*.jar, the same way `mvn package` would locally.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy just the POM first and resolve dependencies before copying any
# source code. Docker caches each layer by its inputs, so as long as
# pom.xml doesn't change, this (slow) dependency download is skipped on
# every later rebuild — only the "COPY backend backend" layer below
# re-runs when you edit a .java file.
COPY backend/pom.xml backend/pom.xml
RUN mvn -f backend/pom.xml -q dependency:go-offline

# db/schema.sql is copied in too: pom.xml's copy-db-schema-for-tests
# plugin execution reads ../db/schema.sql relative to backend/, the same
# single-source-of-truth wiring the integration tests rely on.
COPY backend backend
COPY db db
RUN mvn -f backend/pom.xml -q package -DskipTests

# ── runtime stage: just a JRE + the jar, nothing else ────────────────────
# No Maven, no JDK, no source code in the final image — smaller and
# closer to what you'd actually push to a registry / deploy.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/backend/target/backend-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
