#!/usr/bin/env python3
"""Download the official Telegram APK and build the instrumentation that runs inside it.

The instrumentation must be signed with the same key as its target, so Telegram is re-signed
with a throwaway test key. The script fails if any classes*.dex differs from the official APK,
so the Telegram code under test is unchanged.
"""
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "telegram-work")
SOURCE = pathlib.Path(__file__).resolve().parent / "TelegramPdfE2E.java"
SDK = pathlib.Path(os.environ["ANDROID_HOME"])
BUILD_TOOLS = SDK / "build-tools/36.0.0"
ANDROID_JAR = SDK / "platforms/android-36/android.jar"
URL = os.environ.get("TELEGRAM_APK_URL", "https://telegram.org/dl/android/apk")


def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True, stderr=subprocess.STDOUT)


ROOT.mkdir(parents=True, exist_ok=True)
official = ROOT / "official.apk"
if not official.exists():
    subprocess.run(["curl", "-fL", "--retry", "3", "--max-time", "300", URL, "-o", str(official)], check=True)
badging = run(BUILD_TOOLS / "aapt", "dump", "badging", official)
package = re.search(r"package: name='([^']+)'", badging)[1]
version = re.search(r"versionName='([^']*)'", badging)[1]
(ROOT / "package.txt").write_text(package)
(ROOT / "official-signature.txt").write_text(run(BUILD_TOOLS / "apksigner", "verify", "--print-certs", official))
(ROOT / "official-sha256.txt").write_text(hashlib.sha256(official.read_bytes()).hexdigest())

key = ROOT / "test.jks"
if not key.exists():
    run("keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
        "-alias", "test", "-dname", "CN=Pageno E2E", "-keyalg", "RSA", "-validity", "30")
sign = [BUILD_TOOLS / "apksigner", "sign", "--ks", key, "--ks-pass", "pass:android"]
run(*sign, "--out", ROOT / "telegram.apk", official)
with zipfile.ZipFile(official) as original, zipfile.ZipFile(ROOT / "telegram.apk") as signed:
    dex = [name for name in original.namelist() if name.endswith(".dex")]
    assert dex, "official APK has no dex"
    changed = [name for name in dex if original.read(name) != signed.read(name)]
    assert not changed, f"re-signing changed Telegram bytecode: {changed}"
    hashes = {name: hashlib.sha256(original.read(name)).hexdigest() for name in dex}
(ROOT / "telegram-dex-sha256.json").write_text(json.dumps(hashes, indent=2))

manifest = ROOT / "AndroidManifest.xml"
manifest.write_text(f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="io.github.frequensy23.pageno.e2e">
  <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/>
  <application android:label="Pageno Telegram E2E"/>
  <instrumentation android:name="io.github.frequensy23.pageno.e2e.TelegramPdfE2E"
      android:targetPackage="{package}" android:label="Pageno opens Telegram PDFs in its own task"/>
</manifest>
""")
classes = ROOT / "classes"
classes.mkdir(exist_ok=True)
run("javac", "-Xlint:-options", "-source", "8", "-target", "8", "-classpath", ANDROID_JAR, "-d", classes, SOURCE)
run(BUILD_TOOLS / "d8", "--min-api", "29", "--lib", ANDROID_JAR, "--output", ROOT, *classes.rglob("*.class"))
run(BUILD_TOOLS / "aapt", "package", "-f", "-M", manifest, "-I", ANDROID_JAR, "-F", ROOT / "probe-unsigned.apk")
with zipfile.ZipFile(ROOT / "probe-unsigned.apk", "a") as apk:
    apk.write(ROOT / "classes.dex", "classes.dex")
run(BUILD_TOOLS / "zipalign", "-f", "4", ROOT / "probe-unsigned.apk", ROOT / "probe-aligned.apk")
run(*sign, "--out", ROOT / "probe.apk", ROOT / "probe-aligned.apk")
print(f"Prepared official Telegram {package} {version} and the E2E instrumentation in {ROOT}")
