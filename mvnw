#!/usr/bin/env bash
MAVEN_PROJECTBASEDIR=$(cd "$(dirname "$0")" && pwd)
JAVA_CMD=${JAVA_HOME:-java}
exec "$JAVA_CMD" -jar "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar" "$@"
