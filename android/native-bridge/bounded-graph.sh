#!/usr/bin/env bash
# Re-run an already configured AMD64 Digitalis graph with a bounded Go heap.
set -euo pipefail
: "${LINEAGE_ROOT:?Set LINEAGE_ROOT}"
cd "$LINEAGE_ROOT"
[[ $(cat frameworks/libs/binary_translation/.mcphone-digitalis-pin) == cf5842168cfbc001fe0cdc44ca340fc2525fb269 ]]
[[ -f out/soong/soong.lineage_virtio_x86_64_go.variables ]]
GOGC=25 GOMEMLIMIT=18GiB out/host/linux-x86/bin/soong_build \
 --top "$PWD" --soong_out out/soong --out out \
 --soong_variables out/soong/soong.lineage_virtio_x86_64_go.variables \
 -o out/soong/build.lineage_virtio_x86_64_go.ninja \
 --kati_suffix -lineage_virtio_x86_64_go --kati_enabled \
 -l out/.module_paths/Android.bp.list \
 --available_env out/soong/soong.environment.available \
 --used_env out/soong/soong.environment.used.lineage_virtio_x86_64_go.build Android.bp
printf 'DIGITALIS_BOUNDED_GRAPH_OK\n'
