FROM openjdk:8-jdk-alpine

COPY ./build/libs/token-endpoint-gateway-*.jar token-endpoint-gateway.jar

ENTRYPOINT ["java", "-jar", "/token-endpoint-gateway.jar"]