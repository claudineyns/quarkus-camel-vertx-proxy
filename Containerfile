FROM eclipse-temurin:21-jre-alpine

WORKDIR /deployments

COPY target/quarkus-app/lib/           /deployments/lib/
COPY target/quarkus-app/quarkus/       /deployments/quarkus/
COPY target/quarkus-app/app/           /deployments/app/
COPY target/quarkus-app/quarkus-run.jar /deployments/quarkus-run.jar

EXPOSE 8080
EXPOSE 8443
EXPOSE 9000

# Heap dimensionado via container-awareness da JVM (cgroup limits).
# InitialRAMPercentage=25 e MaxRAMPercentage=62.5 equivalem a -Xms128m -Xmx320m
# num container de 512m, preservando auto-ajuste se o limite mudar.
# Sobrescrever via -e JAVA_TOOL_OPTIONS="..." no podman run ou no Deployment.
ENV JAVA_TOOL_OPTIONS="\
 -XX:InitialRAMPercentage=25.0\
 -XX:MaxRAMPercentage=62.5\
 -XX:MaxMetaspaceSize=160m\
 -XX:ReservedCodeCacheSize=96m\
 -XX:MaxDirectMemorySize=64m\
 -XX:+UseG1GC\
 -XX:MaxGCPauseMillis=200\
 -XX:InitiatingHeapOccupancyPercent=35"

CMD ["java", "-jar", "quarkus-run.jar"]
