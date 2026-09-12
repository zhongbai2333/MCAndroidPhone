import hashlib
from pathlib import Path
import runpy
import tempfile
import zipfile

filter_apk = runpy.run_path(str(Path(__file__).parents[1] / "compact/filter_webview.py"))["filter_apk"]
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    source, output = root / "source.apk", root / "result.apk"
    files = {"AndroidManifest.xml": b"manifest", "classes.dex": b"dex", "lib/x86_64/libwebviewchromium.so": b"64-bit", "lib/x86/libwebviewchromium.so": b"32-bit", "assets/page": b"page", "META-INF/CERT.SF": b"old signature"}
    with zipfile.ZipFile(source, "w") as z:
        for name, data in files.items():
            z.writestr(name, data)
    before = hashlib.sha256(source.read_bytes()).digest()
    filter_apk(source, output)
    with zipfile.ZipFile(output) as z:
        assert set(z.namelist()) == set(files) - {"lib/x86/libwebviewchromium.so", "META-INF/CERT.SF"}
        for name in z.namelist():
            assert z.read(name) == files[name]
    assert hashlib.sha256(source.read_bytes()).digest() == before
    try:
        filter_apk(source, output)
        raise AssertionError("Existing output overwritten")
    except ValueError:
        pass
    with zipfile.ZipFile(source, "a") as z:
        z.writestr("lib/arm64-v8a/libwebviewchromium.so", b"unexpected")
    try:
        filter_apk(source, root / "rejected.apk")
        raise AssertionError("Wrong architecture accepted")
    except ValueError:
        pass
    assert not (root / "rejected.apk").exists()
print("WEBVIEW_FILTER_OK original retained, resources retained, only unused JNI/signature removed, architecture guards")
