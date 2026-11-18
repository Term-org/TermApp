# TermApp

TermOne, TermTwo, TermThree and TermFour: one terminal app built as four independent applications
(`app.term.one`, `app.term.two`, `app.term.three`, `app.term.four`). They share all code and differ
only in application id, name and icon colour.

Related repositories: [TP](https://github.com/Term-org/TP) (packages and bootstrap),
[TCP](https://github.com/Term-org/TCP) (community packages).

## Building

CI builds every flavor on each push to `main` and stores the unsigned release APKs as the artifact
`apks`. Pull requests only build the `termone` debug variant.

Local builds need JDK 17, the Android SDK with NDK 26.1.10909125 and CMake 3.22.1, and Gradle 8.10:

    gradle :app:assembleRelease

## Releasing

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`, push to `main` and wait for CI.
2. Run the **Release** workflow with the tag (for example `v0.1.0`). It signs the APKs from the CI
   run without rebuilding and publishes them in the Releases tab.

Required secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`.

## Bootstrap

The first start downloads the base system (bash, coreutils, apt) from the TP releases. The app
targets API 28 on purpose, because Android 10 and later block running binaries from the app data
directory for newer targets.