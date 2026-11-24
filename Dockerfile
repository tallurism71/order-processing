# One Dockerfile for every module:  docker build --build-arg MODULE=order-service -t order-service .
# Expects the jar from `./gradlew build` (CI builds once, then packages the tested artifact).

FROM eclipse-temurin:21-jre-alpine AS layers
ARG MODULE
WORKDIR /build
COPY ${MODULE}/build/libs/${MODULE}.jar app.jar
# Split into layers: dependencies change rarely, so image pushes/pulls only move the app layer.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
# OpenShift runs containers with a random UID in group 0: make the app dir group-0 owned/writable.
RUN chgrp -R 0 /app && chmod -R g=u /app
USER 1001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher \"$@\"", "--"]
