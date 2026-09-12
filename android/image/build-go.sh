#!/usr/bin/env bash
# Run on a prepared Linux LineageOS build host. No installs, source sync or publishing here.
set -euo pipefail
project=$(cd -- "$(dirname -- "$0")/../.." && pwd)
# Keep the default bounded on developer machines; Android otherwise uses all host cores.
build_jobs=${MCANDROIDPHONE_BUILD_JOBS:-2}
if [[ ! $build_jobs =~ ^[1-9][0-9]*$ ]] || (( ${#build_jobs} > 3 )) || (( build_jobs > 256 )); then
    echo 'MCANDROIDPHONE_BUILD_JOBS must be an integer from 1 to 256.' >&2
    exit 2
fi
: "${LINEAGE_ROOT:?Set LINEAGE_ROOT to a synced LineageOS 23.2 checkout}"
case "${1:-}" in
  arm64) target=virtio_arm64only ;;
  amd64) target=virtio_x86_64 ;;
  *) echo 'Usage: LINEAGE_ROOT=/path/to/lineage bash android/image/build-go.sh arm64|amd64' >&2; exit 2 ;;
esac
profile=${2:-full}
case "$profile" in full|compact|minimal) ;; *) echo 'Profile must be full, compact or minimal.' >&2; exit 2 ;; esac
if [[ $profile != full && $target != virtio_x86_64 ]]; then echo 'Compact profiles currently require AMD64.' >&2; exit 2; fi
export MCANDROIDPHONE_IMAGE_PROFILE="$profile"
if [[ $(uname -s) != Linux ]]; then echo 'Android image builds require the prepared Linux build host.' >&2; exit 2; fi
java "$project/android/image/Prepare.java" "$LINEAGE_ROOT" "$project" --check
java "$project/android/image/Prepare.java" "$LINEAGE_ROOT" "$project"
java "$project/android/image/PrepareCompact.java" "$LINEAGE_ROOT" --check
java "$project/android/image/PrepareCompact.java" "$LINEAGE_ROOT"
cd "$LINEAGE_ROOT"
# Upstream envsetup is not nounset-safe. Keep error/pipe failure handling active.
set +u
source build/envsetup.sh
export AB_OTA_UPDATER=false
breakfast "${target}_go" user
mkdir -p out/mcandroidphone
report=$(mktemp -d "out/mcandroidphone/${target}-XXXXXXXX")
# Inspect resolved variables, not just the text of a late inherited makefile.
variables=$(java "$project/android/image/VerifyGoConfig.java" --variables)
for variable in $variables; do
    value=$(get_build_var "$variable")
    printf '%s=%s\n' "$variable" "$value"
done > "$report/product.properties"
java "$project/android/image/VerifyGoConfig.java" "$report/product.properties"
# Record the inputs before building, so failed builds retain their provenance.
repo manifest -r -o "$report/manifest.xml"
# The sandbox can read this input without Repo writing caches into HOME/.repo.
cp "$report/manifest.xml" out/mcandroidphone/build-manifest.xml.tmp
mv out/mcandroidphone/build-manifest.xml.tmp out/mcandroidphone/build-manifest.xml
git -C "$project" rev-parse HEAD > "$report/mod-base.txt"
# Record dirty overlay hashes too: a commit id alone does not identify local specializations.
find "$project/android/image" -type f ! -path '*/build/*' -exec sha256sum {} \; \
    > "$report/overlay-sha256.txt"
find "$project/android/camera" -type f ! -path '*/build/*' -exec sha256sum {} \; \
    > "$report/camera-sha256.txt"
printf 'BUILD_JOBS=%s\n' "$build_jobs" > "$report/host-build.properties"
free -h > "$report/host-memory.txt"
df -h . > "$report/host-disk.txt"
echo "Building $target Go with $build_jobs parallel jobs."
m -j "$build_jobs" vm-utm-zip
echo "Go image built. Input evidence: $report; runtime and app acceptance are still required."
