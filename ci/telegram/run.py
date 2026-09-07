import pathlib, subprocess

root = pathlib.Path('telegram-work')
out = pathlib.Path('telegram-evidence')
out.mkdir(exist_ok=True)
package = (root/'package.txt').read_text().strip()
def adb(*args, check=True):
    r = subprocess.run(['adb', *map(str,args)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and r.returncode: raise RuntimeError(r.stdout)
    return r.stdout

print(adb('install', '-r', root/'telegram.apk'))
print(adb('install', '-r', root/'probe.apk'))
for mode, folder in [('baseline', 'baseline-apk'), ('fixed', 'fixed-apk')]:
    adb('shell', 'am', 'force-stop', package)
    adb('uninstall', 'io.github.frequensy23.pageno', check=False)
    apk = next(pathlib.Path(folder).glob('*universal*.apk'))
    print(adb('install', '-r', apk))
    adb('shell', 'appops', 'set', 'io.github.frequensy23.pageno', 'MANAGE_EXTERNAL_STORAGE', 'allow')
    adb('logcat', '-c')
    try:
        result = adb('shell', 'am', 'instrument', '-w', '-e', 'mode', mode, 'io.github.frequensy23.telegramprobe/.Probe')
        print(result)
        (out/f'{mode}-instrumentation.txt').write_text(result)
    finally:
        adb('pull', f'/sdcard/Android/data/{package}/files/pageno-probe-{mode}', out, check=False)
        (out/f'{mode}-logcat.txt').write_text(adb('logcat', '-d', check=False))
    assert f'PASS {mode}:' in result, result
print('PASS: official Telegram reproduces baseline defect and passes with fixed Pageno')
