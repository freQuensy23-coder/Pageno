import hashlib, json, pathlib, re, subprocess, zipfile

root = pathlib.Path('telegram-work')
root.mkdir(exist_ok=True)
sdk = pathlib.Path(__import__('os').environ['ANDROID_HOME'])
bt = sdk / 'build-tools/36.0.0'
jar = sdk / 'platforms/android-36/android.jar'
def run(*args):
    return subprocess.check_output(list(map(str, args)), text=True, stderr=subprocess.STDOUT)

subprocess.run(['curl', '-fL', '--retry', '3', '--max-time', '180', 'https://telegram.org/dl/android/apk', '-o', str(root/'official.apk')], check=True)
badging = run(bt/'aapt', 'dump', 'badging', root/'official.apk')
(root/'badging.txt').write_text(badging)
package = re.search(r"package: name='([^']+)'", badging)[1]
signature = run(bt/'apksigner', 'verify', '--print-certs', root/'official.apk')
(root/'original-signature.txt').write_text(signature)
(root/'package.txt').write_text(package)
run('keytool', '-genkeypair', '-keystore', root/'test.jks', '-storepass', 'android', '-keypass', 'android', '-alias', 'test', '-dname', 'CN=Isolated emulator test', '-keyalg', 'RSA', '-validity', '30')
run(bt/'apksigner', 'sign', '--ks', root/'test.jks', '--ks-pass', 'pass:android', '--out', root/'telegram.apk', root/'official.apk')
with zipfile.ZipFile(root/'official.apk') as original, zipfile.ZipFile(root/'telegram.apk') as signed:
    dex = [n for n in original.namelist() if n.endswith('.dex')]
    assert dex and all(original.read(n) == signed.read(n) for n in dex), 'Telegram bytecode changed'
    (root/'bytecode-verification.json').write_text(json.dumps({n:hashlib.sha256(original.read(n)).hexdigest() for n in dex}, indent=2))
(root/'official-sha256.txt').write_text(hashlib.sha256((root/'official.apk').read_bytes()).hexdigest())
manifest = root/'AndroidManifest.xml'
manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.frequensy23.telegramprobe">
<uses-sdk android:minSdkVersion="23" android:targetSdkVersion="36"/>
<application android:label="Telegram PDF probe"/>
<instrumentation android:name="io.github.frequensy23.telegramprobe.Probe" android:targetPackage="{package}"/>
</manifest>''')
(root/'classes').mkdir(exist_ok=True)
run('javac', '-source', '8', '-target', '8', '-classpath', jar, '-d', root/'classes', 'ci/telegram/Probe.java')
run(bt/'d8', '--lib', jar, '--output', root, *list((root/'classes').rglob('*.class')))
run(bt/'aapt', 'package', '-f', '-M', manifest, '-I', jar, '-F', root/'probe-unsigned.apk')
with zipfile.ZipFile(root/'probe-unsigned.apk', 'a') as apk: apk.write(root/'classes.dex', 'classes.dex')
run(bt/'zipalign', '-f', '4', root/'probe-unsigned.apk', root/'probe-aligned.apk')
run(bt/'apksigner', 'sign', '--ks', root/'test.jks', '--ks-pass', 'pass:android', '--out', root/'probe.apk', root/'probe-aligned.apk')
print('Prepared official Telegram:', package)
