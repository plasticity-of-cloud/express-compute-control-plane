#!/bin/bash
NATIVE=$(find ./ecp-cli/target -maxdepth 1 -name "*-runner" -executable | head -1)
JAR=$(find ./ecp-cli/target -maxdepth 1 -name "*-runner.jar" | head -1)

if [ -n "$NATIVE" ]; then
  CMD="$NATIVE"
elif [ -n "$JAR" ]; then
  CMD="java -jar $JAR"
else
  echo "No CLI binary found in ecp-cli/target" >&2
  exit 1
fi

$CMD "$@"
