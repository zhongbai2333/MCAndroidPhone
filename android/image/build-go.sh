#!/usr/bin/env bash
# Run on a prepared Linux LineageOS build host. No installs, source sync or publishing here.
set -euo pipefail
project=$(cd -- "$(dirname -- "$0")/../.." && pwd)
: "${LINEAGE_ROOT:?Set LINEAGE_ROOT to a synced LineageOS 23.2 checkout}"
case "${1:-}" in
  arm64) target=virtio_arm64only ;;
  amd64) target=virtio_x86_64 ;;
  *) echo 'Usage: LINEAGE_ROOT=/path/to/lineage bash android/image/build-go.sh arm64|amd64' >&2; exit 2 ;;
esac
if [[ $(uname -s) != Linux ]]; then echo 'Android image builds require the prepared Linux build host.' >&2; exit 2; fi
java "$project/android/image/Prepare.java" "$LINEAGE_ROOT" "$project" --check
java "$project/android/image/Prepare.java" "$LINEAGE_ROOT" "$project"
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
git -C "$project" rev-parse HEAD > "$report/mod-base.txt"
# Record dirty overlay hashes too: a commit id alone does not identify local specializations.
find "$project/android/image" -type f ! -path '*/build/*' -exec sha256sum {} \; \
    > "$report/overlay-sha256.txt"
find "$project/android/camera" -type f ! -path '*/build/*' -exec sha256sum {} \; \
    > "$report/camera-sha256.txt"
m vm-utm-zip
echo "Go image built. Input evidence: $report; runtime and app acceptance are still required."
