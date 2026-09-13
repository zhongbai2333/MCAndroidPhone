#!/usr/bin/env bash
# Uses only the already-built Linux AOSP toolchain. Signs with AOSP's public development test key.
set -euo pipefail
: "${LINEAGE_ROOT:?Set LINEAGE_ROOT}"
source_dir=$(cd -- "$(dirname -- "$0")" && pwd)
output=${1:?Pass a NEW absolute output directory}
[[ $output == /* && ! -e $output ]]
mkdir -p "$output/classes" "$output/dex" "$output/lib/arm64-v8a"
cd "$LINEAGE_ROOT"
java=prebuilts/jdk/jdk21/linux-x86/bin/java
javac=prebuilts/jdk/jdk21/linux-x86/bin/javac
jar=prebuilts/jdk/jdk21/linux-x86/bin/jar
sdk=prebuilts/sdk/current/public/android.jar
prebuilts/clang/host/linux-x86/clang-r563880/bin/clang --target=aarch64-linux-android26 \
 -shared -fPIC -O2 -nostdlib -fuse-ld=lld -Wl,-z,max-page-size=16384 \
 "$source_dir/probe.c" -o "$output/lib/arm64-v8a/libmcphone_arm64_probe.so"
"$javac" --release 8 -cp "$sdk" -d "$output/classes" "$source_dir/MainActivity.java"
"$jar" cf "$output/classes.jar" -C "$output/classes" .
"$java" -cp prebuilts/r8/r8.jar com.android.tools.r8.D8 --min-api 26 --lib "$sdk" --output "$output/dex" "$output/classes.jar"
out/host/linux-x86/bin/aapt2 link -I "$sdk" --manifest "$source_dir/AndroidManifest.xml" -o "$output/unsigned.apk"
"$jar" uf "$output/unsigned.apk" -C "$output/dex" classes.dex -C "$output" lib/arm64-v8a/libmcphone_arm64_probe.so
"$java" -Djava.library.path=out/host/linux-x86/lib64 -jar out/host/linux-x86/framework/signapk.jar \
 build/make/target/product/security/testkey.x509.pem build/make/target/product/security/testkey.pk8 \
 "$output/unsigned.apk" "$output/mcphone-arm64-probe.apk"
printf 'ARM64_PROBE_APK_BUILT %s\n' "$output/mcphone-arm64-probe.apk"
