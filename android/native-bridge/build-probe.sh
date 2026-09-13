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
if [[ ${MCANDROIDPHONE_REUSE_GRAPH:-false} == true ]]; then
    # Only after a successful graph generation for this exact prepared product.
    prebuilts/build-tools/linux-x86/bin/ninja -j "${MCANDROIDPHONE_BUILD_JOBS:-12}" \
        -f out/combined-lineage_virtio_x86_64_go.ninja \
        out/soong/.intermediates/frameworks/libs/binary_translation/libberberis_arm64/android_x86_64_sandybridge_shared/libberberis_arm64.so \
        out/soong/.intermediates/frameworks/libs/binary_translation/program_runner/berberis_program_runner_arm64/android_x86_64_sandybridge/berberis_program_runner_arm64
else
    m -j "${MCANDROIDPHONE_BUILD_JOBS:-12}" libberberis_arm64 berberis_program_runner_arm64
fi
printf 'DIGITALIS_COMPILE_PROBE_OK; image assembly, ARM64 APK, graphics and SELinux acceptance still required.\n'
