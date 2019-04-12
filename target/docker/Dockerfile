FROM adoptopenjdk/openjdk11-openj9:latest
RUN mkdir /opt/app
COPY proxy.jar /opt/app
ENTRYPOINT ["java", "-jar", "/opt/app/proxy.jar"]
