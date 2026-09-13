#!/usr/bin/env python3
"""Opt-in, reversible Digitalis source overlay for the prepared Lineage build host."""
import argparse, hashlib, json, shutil, subprocess, tarfile
from pathlib import Path
PIN = "cf5842168cfbc001fe0cdc44ca340fc2525fb269"
ARCHIVE_SHA = "42619845779d6b985062c0b93fddb5683ddd1072c5918aec15a56efe82715b64"
parser = argparse.ArgumentParser()
parser.add_argument("root", type=Path)
parser.add_argument("archive", type=Path)
parser.add_argument("--restore", action="store_true")
args = parser.parse_args()
root = args.root.resolve()
if not (root / "build/envsetup.sh").is_file(): raise SystemExit("Not an Android source tree")
source = root / "frameworks/libs/binary_translation"
backup = root.parent / (root.name + "-before-digitalis")
board = root / "device/virt/virtio_x86_64/BoardConfig.mk"
product = root / "device/virt/virtio_x86_64/lineage_virtio_x86_64_go.mk"
marker = "# MCANDROIDPHONE_DIGITALIS_V1"
board_block = """\n# MCANDROIDPHONE_DIGITALIS_V1
ifeq ($(TARGET_PRODUCT),lineage_virtio_x86_64_go)
TARGET_NATIVE_BRIDGE_ARCH := arm64
TARGET_NATIVE_BRIDGE_ARCH_VARIANT := armv8-a
TARGET_NATIVE_BRIDGE_CPU_VARIANT := generic
TARGET_NATIVE_BRIDGE_ABI := arm64-v8a
endif # MCANDROIDPHONE_DIGITALIS_V1
"""
product_block = """\n# MCANDROIDPHONE_DIGITALIS_V1
$(call inherit-product, frameworks/libs/binary_translation/enable_arm64_to_x86_64.mk)
"""
if args.restore:
    state = json.loads((backup / "state.json").read_text())
    for file, old in ((board, "BoardConfig.mk"), (product, "product.mk")):
        expected = state[file.name]
        if hashlib.sha256(file.read_bytes()).hexdigest() != expected: raise SystemExit("Configuration changed since preparation; restore manually to preserve edits")
    if (source / ".mcphone-digitalis-pin").read_text().strip() != PIN: raise SystemExit("Unexpected translator state")
    retained = backup / "digitalis-retained"
    if retained.exists(): raise SystemExit("Retained source already exists")
    source.rename(retained)
    (backup / "binary_translation").rename(source)
    shutil.copyfile(backup / "BoardConfig.mk", board)
    shutil.copyfile(backup / "product.mk", product)
    print("DIGITALIS_RESTORED; experimental sources retained in", retained)
    raise SystemExit(0)
if backup.exists(): raise SystemExit("Backup already exists; do not overlay twice")
if marker in board.read_text() or marker in product.read_text(): raise SystemExit("Already prepared")
if subprocess.check_output(["git", "-C", str(source), "status", "--porcelain"], text=True).strip(): raise SystemExit("Translator checkout has changes; preserve them first")
archive = args.archive.resolve()
if hashlib.file_digest(archive.open("rb"), "sha256").hexdigest() != ARCHIVE_SHA: raise SystemExit("Wrong fixed source archive")
backup.mkdir()
shutil.copyfile(board, backup / "BoardConfig.mk")
shutil.copyfile(product, backup / "product.mk")
staging = backup / "staging"
staging.mkdir()
with tarfile.open(archive) as tar:
    for entry in tar.getmembers():
        if not entry.isfile() and not entry.isdir(): raise SystemExit("Unexpected archive link/device")
    tar.extractall(staging, filter="data")
roots = list(staging.iterdir())
if len(roots) != 1 or not (roots[0] / "enable_arm64_to_x86_64.mk").is_file(): raise SystemExit("Invalid source archive")
source.rename(backup / "binary_translation")
roots[0].rename(source)
# Preserve Repo's original git linkage so local diffs remain inspectable and never imply an upstream commit.
gitlink = backup / "binary_translation/.git"
if gitlink.is_symlink(): (source / ".git").symlink_to(gitlink.readlink())
elif gitlink.is_file(): shutil.copyfile(gitlink, source / ".git")
else: raise SystemExit("Expected Repo .git link/file; original tree is in backup")
(source / ".mcphone-digitalis-pin").write_text(PIN + "\n")
board.write_text(board.read_text() + board_block)
product.write_text(product.read_text() + product_block)
state = {file.name: hashlib.sha256(file.read_bytes()).hexdigest() for file in (board, product)}
state.update(pin=PIN, archiveSha256=ARCHIVE_SHA, status="source-prepared-not-boot-validated")
(backup / "state.json").write_text(json.dumps(state, indent=2) + "\n")
print("DIGITALIS_PREPARED", PIN, "backup=", backup)
