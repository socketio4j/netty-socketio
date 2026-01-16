FROM eclipse-temurin:25.0.1_8-jre-alpine-3.23


WORKDIR /app

COPY target/netty-socketio-core-example-*.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS=""
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
ENV OTEL_METRICS_EXPORTER=otlp
ENV OTEL_TRACES_EXPORTER=none
ENV OTEL_LOGS_EXPORTER=none

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
