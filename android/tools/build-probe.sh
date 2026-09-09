#!/bin/sh
# Development-only. R8_JAR is a locally supplied Google D8 distribution; no automatic downloads.
set -eu
project=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
: "${R8_JAR:?Set R8_JAR to a Google R8/D8 JAR}"
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}
out="$project/android/tools/build"
mkdir -p "$out/classes" "$out/dex"
"${java_bin}javac" --release 8 -Xlint:-options -d "$out/classes" \
    "$project/src/main/java/com/zhongbai233/mcandroidphone/environment/EnvironmentPacket.java" \
    "$project/android/tools/EnvironmentProbe.java" \
    "$project/android/tools/CameraProbe.java"
"${java_bin}jar" cf "$out/probe-classes.jar" -C "$out/classes" .
"${java_bin}java" -cp "$R8_JAR" com.android.tools.r8.D8 --min-api 26 --output "$out/dex" "$out/probe-classes.jar"
"${java_bin}jar" cf "$out/environment-probe.jar" -C "$out/dex" classes.dex
printf '%s\n' "$out/environment-probe.jar"
