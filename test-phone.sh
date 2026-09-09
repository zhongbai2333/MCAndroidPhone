#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -n "${JAVA_HOME:-}" ]; then
  exec "$JAVA_HOME/bin/java" scripts/Dev.java "$@"
fi
exec java scripts/Dev.java "$@"
