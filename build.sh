#!/bin/bash
rm vertx-graal*
native-image \
 --verbose \
 --no-server \
 -Dio.netty.noUnsafe=true  \
 -H:Name=hello-world \
 -H:ReflectionConfigurationFiles=./reflectconfigs/netty.json \
 -H:+ReportUnsupportedElementsAtRuntime \
 -H:+JNI \
 -Dfile.encoding=UTF-8 \
 -jar target/proxy-1.0.0-SNAPSHOT-fat.jar


