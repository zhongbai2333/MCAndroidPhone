#!/usr/bin/env bash
# Compile translator and its dependency graph first; do not publish or replace distribution images.
set -eo pipefail
: "${LINEAGE_ROOT:?Set LINEAGE_ROOT}"
cd "$LINEAGE_ROOT"
[[ $(cat frameworks/libs/binary_translation/.mcphone-digitalis-pin) == cf5842168cfbc001fe0cdc44ca340fc2525fb269 ]]
source build/envsetup.sh
export AB_OTA_UPDATER=false
breakfast virtio_x86_64_go user
mkdir -p out/mcandroidphone/digitalis
for variable in TARGET_NATIVE_BRIDGE_ARCH TARGET_NATIVE_BRIDGE_ABI PRODUCT_SYSTEM_PROPERTIES; do
    printf '%s=%s\n' "$variable" "$(get_build_var "$variable")"
done > out/mcandroidphone/digitalis/product.properties
m -j "${MCANDROIDPHONE_BUILD_JOBS:-12}" libberberis_arm64 berberis_program_runner_arm64
printf 'DIGITALIS_COMPILE_PROBE_OK; image assembly, ARM64 APK, graphics and SELinux acceptance still required.\n'
