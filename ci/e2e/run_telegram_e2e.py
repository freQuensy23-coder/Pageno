#!/usr/bin/env python3
"""Run the Telegram -> Pageno end-to-end scenarios on a connected device or emulator.

  run_telegram_e2e.py --apk Pageno.apk --label head --expect pass
  run_telegram_e2e.py --apk Pageno-known-bad.apk --label control --expect bug

--expect pass: every scenario must pass.
--expect bug: the build is known to open Telegram PDFs inside Telegram's task; the test must
catch that. This control keeps the test from passing without checking anything.
"""
import argparse
import pathlib
import re
import subprocess
import sys

PAGENO = "io.github.frequensy23.pageno"
PROBE = "io.github.frequensy23.pageno.e2e/.TelegramPdfE2E"
# The instrumentation sets up the PDF viewers each scenario needs.
SCENARIOS = ["chooser_just_once", "open_once", "open_twice"]
BUG_MARKERS = (
    "READER_IN_TELEGRAM_TASK", "PAGENO_ACTIVITY_IN_TELEGRAM_TASK", "READER_NOT_TASK_ROOT",
    "TELEGRAM_NOT_SHOWN_FROM", "PDF_CLOSED_BY_RETURN_TO_TELEGRAM",
)

parser = argparse.ArgumentParser()
parser.add_argument("--apk", required=True, type=pathlib.Path)
parser.add_argument("--label", required=True)
parser.add_argument("--expect", choices=["pass", "bug"], required=True)
parser.add_argument("--telegram-work", default="telegram-work", type=pathlib.Path)
parser.add_argument("--out", default="telegram-e2e-results", type=pathlib.Path)
parser.add_argument("--scenarios", default=",".join(SCENARIOS))
args = parser.parse_args()
out = args.out / args.label
out.mkdir(parents=True, exist_ok=True)
telegram = (args.telegram_work / "package.txt").read_text().strip()


def adb(*command, check=True, timeout=180):
    result = subprocess.run(["adb", *map(str, command)], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"adb {' '.join(map(str, command))}\n{result.stdout}")
    return result.stdout


subprocess.run(["adb", "wait-for-device"], check=True)
print(adb("shell", "getprop", "ro.build.fingerprint").strip())
print(adb("install", "-r", "-g", args.telegram_work / "telegram.apk"))
print(adb("install", "-r", args.telegram_work / "probe.apk"))
adb("uninstall", PAGENO, check=False)
print(adb("install", "-r", "-g", args.apk))
adb("shell", "appops", "set", PAGENO, "MANAGE_EXTERNAL_STORAGE", "allow", check=False)
adb("shell", "settings", "put", "global", "package_verifier_user_consent", "-1", check=False)

results = {}
for scenario in args.scenarios.split(","):
    assert scenario in SCENARIOS, scenario
    for package in (telegram, PAGENO):
        adb("shell", "am", "force-stop", package)
    adb("shell", "rm", "-rf", f"/sdcard/Android/data/{telegram}/files/pageno-e2e/{scenario}", check=False)
    adb("logcat", "-c", check=False)
    try:
        output = adb("shell", "am", "instrument", "-w", "-r", "-e", "scenario", scenario, PROBE, timeout=420)
    except subprocess.TimeoutExpired as error:
        output = f"TIMEOUT {error}"
    (out / f"{scenario}-instrumentation.txt").write_text(output)
    adb("pull", f"/sdcard/Android/data/{telegram}/files/pageno-e2e/{scenario}", out, check=False)
    (out / f"{scenario}-logcat.txt").write_text(adb("logcat", "-d", check=False))
    (out / f"{scenario}-crash.txt").write_text(adb("logcat", "-d", "-b", "crash", check=False))
    match = re.search(rf"E2E_RESULT {scenario} (PASS|FAIL|SKIP)(.*)", output)
    if match:
        failure = out / scenario / "failure.txt"
        detail = failure.read_text() if match.group(1) == "FAIL" and failure.exists() else match.group(2).strip()
        results[scenario] = (match.group(1), detail)
    else:
        results[scenario] = ("FAIL", "instrumentation did not report a result:\n" + output[-4000:])

print(f"\n===== Telegram E2E: {args.label} ({args.apk.name}), expect {args.expect} =====")
for scenario, (verdict, detail) in results.items():
    print(f"--- {scenario}: {verdict}")
    if verdict != "PASS":
        print(detail)
summary = "\n".join(f"{scenario}: {verdict}" for scenario, (verdict, _) in results.items())
(out / "summary.txt").write_text(summary + "\n")

ran = {s: r for s, r in results.items() if r[0] != "SKIP"}
if args.expect == "pass":
    ok = bool(ran) and all(verdict == "PASS" for verdict, _ in ran.values())
    print("RESULT:", "PASS" if ok else "FAIL")
else:
    caught = [s for s, (verdict, detail) in ran.items()
              if verdict == "FAIL" and any(marker in detail for marker in BUG_MARKERS)]
    ok = bool(caught)
    print("RESULT:", f"known bug detected in {caught}" if ok else "the known-bad build was NOT detected")
sys.exit(0 if ok else 1)
