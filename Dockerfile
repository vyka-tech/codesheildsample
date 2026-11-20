# Multi-stage build: `docker build .` compiles the code with Maven and produces a
# Lambda container image. CodeShield AI uses this same command to validate fixes.

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- Runtime stage (AWS Lambda Java 17 base image) ----
FROM public.ecr.aws/lambda/java:17
COPY --from=build /src/target/classes ${LAMBDA_TASK_ROOT}
COPY --from=build /src/target/dependency/ ${LAMBDA_TASK_ROOT}/lib/
CMD ["com.codeshield.sample.Handler::handleRequest"]
