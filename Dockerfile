# Build check only. The Lambda is deployed as a ZIP (see deploy.ps1), not as an image.
# CodeShield AI validates a fix by running `docker build .`, which compiles the project and
# builds the Lambda zip with Maven. The build fails if the fix breaks compilation.
FROM maven:3.9-eclipse-temurin-17
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests && test -f target/codesheildsample.zip
