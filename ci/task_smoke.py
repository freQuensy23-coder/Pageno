#!/usr/bin/env python3
"""Exercise repeated ACTION_VIEW launches on an Android emulator."""
import pathlib
import re
import subprocess
import time

PACKAGE = "io.github.frequensy23.pageno"
READER = PACKAGE + "/com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity"
OUT = pathlib.Path("task-smoke-results")
OUT.mkdir(exist_ok=True)

def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, stderr=subprocess.STDOUT)

# Small valid one-page PDF; no external fixture download.
stream = b"BT /F1 24 Tf 40 100 Td (Pageno task test) Tj ET\n"
objects = [
    b"<< /Type /Catalog /Pages 2 0 R >>",
    b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"endstream",
]
pdf = bytearray(b"%PDF-1.4\n")
offsets = [0]
for number, obj in enumerate(objects, 1):
    offsets.append(len(pdf))
    pdf += str(number).encode() + b" 0 obj\n" + obj + b"\nendobj\n"
xref = len(pdf)
pdf += b"xref\n0 6\n0000000000 65535 f \n"
for offset in offsets[1:]:
    pdf += f"{offset:010d} 00000 n \n".encode()
pdf += f"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
fixture = OUT / "pageno-task-test.pdf"
fixture.write_bytes(pdf)
apk = sorted(pathlib.Path("apk").glob("*universal*.apk"))[0]
print(adb("install", "-r", str(apk)))
adb("shell", "appops", "set", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
adb("push", str(fixture), "/sdcard/Download/pageno-task-test.pdf")
adb("logcat", "-c")

def reader_tasks():
    dump = adb("shell", "dumpsys", "activity", "recents")
    (OUT / "recents.txt").write_text(dump)
    blocks = re.split(r"\* Recent #\d+:", dump)[1:]
    ids = set()
    for block in blocks:
        if "mActivityComponent=" + READER not in block:
            continue
        match = re.search(r"Task\{[^}\n]*#(\d+)", block)
        if match:
            ids.add(int(match.group(1)))
    return ids

try:
    observed = []
    for count in (1, 2):
        print(adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
                  "-d", "file:///sdcard/Download/pageno-task-test.pdf",
                  "-t", "application/pdf", "-n", READER))
        deadline = time.monotonic() + 30
        tasks = set()
        while time.monotonic() < deadline:
            tasks = reader_tasks()
            if len(tasks) >= count:
                break
            time.sleep(1)
        assert len(tasks) == count, f"Expected {count} document tasks, got {tasks}"
        observed.append(tasks)
    assert observed[0] < observed[1], "Reopening the same URI must preserve the previous task"
    # Reproduce Telegram's request-code-500 launch with a private content provider.
    caller = "io.github.frequensy23.intentcaller"
    caller_component = caller + "/.CallerActivity"
    print(adb("install", "-r", str(next(pathlib.Path("caller-apk").glob("*.apk")))))
    for index, mode in enumerate(("result", "result", "plain"), 3):
        adb("logcat", "-c")
        print(adb("shell", "am", "start", "-W", "-n", caller_component, "--es", "mode", mode))
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            tasks = reader_tasks()
            logs = adb("logcat", "-d")
            if len(tasks) == index and "PDF_OPEN uid=" in logs:
                break
            time.sleep(1)
        assert len(tasks) == index, f"{mode}: reader stayed inside caller task: {tasks}"
        assert "PDF_OPEN uid=" in logs, "Forwarded content URI grant was lost"
        time.sleep(3)
        dump = adb("shell", "dumpsys", "activity", "activities")
        (OUT / f"activities-{index}.txt").write_text(dump)
        # Caller task must contain no reader activity; check each task block.
        for block in re.split(r"(?m)^  \* Task", dump):
            if "Hist #" in block and caller + "/.CallerActivity" in block:
                assert READER not in block, "Reader still belongs to the caller task"
        with (OUT / f"reader-{index}.png").open("wb") as image:
            subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=image, check=True)
        print(f"PASS: {mode}, separate tasks={tasks}, content URI opened")
    time.sleep(3)
    crashes = adb("logcat", "-d", "-b", "crash")
    (OUT / "crashes.txt").write_text(crashes)
    assert PACKAGE not in crashes, crashes
    subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_APP_SWITCH"], check=True)
    time.sleep(1)
    with (OUT / "recents.png").open("wb") as image:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=image, check=True)
    print("PASS: repeated ACTION_VIEW for the same PDF creates two distinct recent tasks:", observed)
finally:
    (OUT / "logcat.txt").write_text(adb("logcat", "-d"))
