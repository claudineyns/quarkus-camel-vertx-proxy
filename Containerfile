FROM eclipse-temurin:21-jre-alpine

WORKDIR /deployments

COPY target/quarkus-app/lib/           /deployments/lib/
COPY target/quarkus-app/quarkus/       /deployments/quarkus/
COPY target/quarkus-app/app/           /deployments/app/
COPY target/quarkus-app/quarkus-run.jar /deployments/quarkus-run.jar

EXPOSE 8080
EXPOSE 8443
EXPOSE 9000

# Heap dimensionado automaticamente pelo container-awareness da JVM (cgroup limits).
# Sobrescrever via -e JAVA_TOOL_OPTIONS="..." no podman run ou no Deployment.
ENV JAVA_TOOL_OPTIONS="-XX:MaxMetaspaceSize=160m \
                       -XX:ReservedCodeCacheSize=96m \
                       -XX:MaxDirectMemorySize=64m"

CMD ["java", "-jar", "quarkus-run.jar"]
