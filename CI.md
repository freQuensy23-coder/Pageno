# Builds and releases

The Android workflow runs on pushes to main, pull requests, v* tags and manual dispatch. It calls the Telegram E2E workflow.

## Continuous integration

Ubuntu 24.04, Temurin Java 17, Android API 36 and NDK 30.0.14904198 are provisioned explicitly. Native libraries are cached by the native source and NDK version.

CI uses the upstream checksum-pinned prebuilt PDFium and builds FreeType, libpng and the JNI bridge for arm64-v8a, armeabi-v7a, x86 and x86_64. It checks that the native runtime libraries exist before assembling APKs. This differs from upstream's default PDFium-from-source optimization path.

The workflow runs Python download-tool tests, Gradle testDebugUnitTest and lintDebug, and builds debug and optimized unsigned release APKs. Test and lint reports are retained for 14 days; release APKs for 30 days.

Download the Pageno-debug artifact from a successful Actions run for an installable debug build. Debug signing keys are runner-generated, so debug builds from different runs may require uninstalling the prior build. Pageno uses application ID `io.github.frequensy23.pageno`, so it can coexist with MJ PDF.

## Release delivery

Every push to main publishes a GitHub Release `v<versionName>-build.<run>`, marked latest, once build, unit tests, lint, task-smoke and the Telegram E2E all pass. Pushing a `v*` tag publishes a release under that tag. Assets are APKs (universal and per ABI) signed with the Pageno release key, plus SHA-256 checksums. Latest: https://github.com/freQuensy23-coder/Pageno/releases/latest

The release key lives only in the repository secrets `PAGENO_KEYSTORE_B64` and `PAGENO_KEYSTORE_PASSWORD` (alias `pageno`, certificate SHA-256 `C5:93:DF:4C:3F:0E:56:D5:2A:9A:8A:AD:05:74:80:F8:48:BB:F4:2E:2C:17:D5:72:E7:9C:E6:35:CA:8A:47:8B`). Never commit it. Builds signed with any other key (CI debug builds, earlier artifacts) cannot be updated in place; uninstall them once first. The workflow does not publish to Google Play.

## Local build

Install Java 17, Android command-line tools, API 36, build-tools 36.0.0, NDK 30.0.14904198, CMake and Python requests. Set ANDROID_HOME and ANDROID_NDK to their installed paths.

```sh
mkdir -p PdfiumAndroid/src/main/jni/lib/{arm64-v8a,armeabi-v7a,x86,x86_64}
python3 build_dependencies.py --pdfium prebuilt
bash ./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
```

The upstream README's statement that native binaries are included does not apply to this imported source snapshot. Build the native dependencies first.

## Document task regression

The task-smoke job installs the debug APK on Android 16 (API 36). It opens the same PDF twice through ACTION_VIEW, then opens a content URI from a caller app with startActivityForResult. It checks for separate document tasks, no reader left in the caller's task, and no crash.

## Telegram end-to-end test

The Telegram E2E workflow (`.github/workflows/telegram-e2e.yml`) downloads the official Telegram APK and re-signs it with a test key; its dex files stay byte-identical. It runs `ci/e2e/TelegramPdfE2E.java` inside Telegram on Android 16 phone and tablet emulators. The test calls Telegram's own `AndroidUtilities.openForView` from Telegram's resumed activity, the same startActivityForResult(..., 500) call Telegram makes for a document tapped in a chat. Scenarios: Pageno as the only PDF viewer, two PDFs in a row, and the Android "Open with" chooser with "Just once".

For each opened PDF the test fails if:
- Android created a reader activity inside Telegram's task at any point (`wm_create_activity`);
- the settled reader is not the root of its own task, or any Pageno activity stays in Telegram's task;
- the document title is not shown;
- returning to Telegram from Recents or from its launcher icon closes the PDF, or switching back does not restore it.

The same test also runs against a known-bad build (`c305726`, which relaunched itself out of Telegram's task) and must fail there, so it cannot go green without checking anything. Screenshots, dumpsys output and logcat are uploaded as `telegram-e2e-*` artifacts. Run locally against a booted emulator with `python3 ci/e2e/prepare_telegram.py` and `python3 ci/e2e/run_telegram_e2e.py --apk <apk> --label local --expect pass`.

Not covered: Samsung One UI (no emulator image exists) and a logged-in Telegram chat UI. Telegram's opener is called directly, without tapping a message.
