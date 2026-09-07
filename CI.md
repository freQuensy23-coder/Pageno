# Builds and releases

The Android workflow runs on pushes to main, pull requests, v* tags and manual dispatch.

## Continuous integration

Ubuntu 24.04, Temurin Java 17, Android API 36 and NDK 30.0.14904198 are provisioned explicitly. Native libraries are cached by the native source and NDK version.

CI uses the upstream checksum-pinned prebuilt PDFium and builds FreeType, libpng and the JNI bridge for arm64-v8a, armeabi-v7a, x86 and x86_64. It checks that the native runtime libraries exist before assembling APKs. This differs from upstream's default PDFium-from-source optimization path.

The workflow runs Python download-tool tests, Gradle testDebugUnitTest and lintDebug, and builds debug and optimized unsigned release APKs. Test and lint reports are retained for 14 days; release APKs for 30 days.

Download the Pageno-debug artifact from a successful Actions run for an installable debug build. Debug signing keys are runner-generated, so debug builds from different runs may require uninstalling the prior build. The initial fork still uses the original MJ PDF application ID.

## Release delivery

Push a version tag such as v0.1.0 to build the tagged source and create a draft GitHub Release containing unsigned APKs and SHA-256 checksums. Publishing is left to the maintainer. Release creation runs only after all build checks pass.

A release keystore has not been configured. Release APKs must be signed before installation/distribution; do not commit a keystore or passwords. The workflow does not publish to Google Play.

## Local build

Install Java 17, Android command-line tools, API 36, build-tools 36.0.0, NDK 30.0.14904198, CMake and Python requests. Set ANDROID_HOME and ANDROID_NDK to their installed paths.

```sh
mkdir -p PdfiumAndroid/src/main/jni/lib/{arm64-v8a,armeabi-v7a,x86,x86_64}
python3 build_dependencies.py --pdfium prebuilt
bash ./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug
```

The upstream README's statement that native binaries are included does not apply to this imported source snapshot. Build the native dependencies first.
