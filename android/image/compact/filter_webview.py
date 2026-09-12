#!/usr/bin/env python3
"""Remove unused 32-bit JNI from a 64-bit-only product; Soong signs the result."""
import sys
import zipfile
from pathlib import Path

def filter_apk(source, destination):
    source, destination = Path(source), Path(destination)
    if destination.exists():
        raise ValueError("Output already exists")
    with zipfile.ZipFile(source) as src:
        names = src.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entry")
        if not {"AndroidManifest.xml", "classes.dex", "lib/x86_64/libwebviewchromium.so"}.issubset(names):
            raise ValueError("Unexpected AMD64 WebView layout")
        if any(n.startswith("lib/") and len(n.split("/")) > 2 and n.split("/")[1] not in ("x86", "x86_64") for n in names):
            raise ValueError("Unexpected JNI architecture")
        with zipfile.ZipFile(destination, "x") as dst:
            for item in src.infolist():
                name = item.filename
                old_signature = name == "META-INF/MANIFEST.MF" or (name.startswith("META-INF/") and name.endswith((".SF", ".RSA", ".DSA", ".EC")))
                if name.startswith("lib/x86/") or old_signature:
                    continue
                with src.open(item) as data, dst.open(item, "w") as output:
                    while block := data.read(1024 * 1024):
                        output.write(block)

if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("filter_webview.py INPUT.apk NEW_OUTPUT.apk")
    filter_apk(sys.argv[1], sys.argv[2])
