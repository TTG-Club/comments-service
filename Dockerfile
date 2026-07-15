FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /workspace/app

COPY --link pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

COPY --link src src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -B -DskipTests

RUN mkdir -p target/dependency && (cd target/dependency; jar -xf ../comment.ttg.club.jar)

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

ARG DEPENDENCY=/workspace/app/target/dependency
COPY --from=build ${DEPENDENCY}/BOOT-INF/lib /app/lib
COPY --from=build ${DEPENDENCY}/META-INF /app/META-INF
COPY --from=build ${DEPENDENCY}/BOOT-INF/classes /app

ENTRYPOINT ["java","-cp",".:lib/*","club.ttg.comment.CommentApplication"]
