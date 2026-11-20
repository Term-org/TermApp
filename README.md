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

Run the **Tag** workflow on `main` with the new version, for example `v0.2.0`. It

1. sets `versionName` to `0.2.0` and raises `versionCode` by one in `app/build.gradle.kts`,
2. commits that change to `main` and creates the tag `v0.2.0` in the same push,
3. starts **CI** for the tag.

When that CI run succeeds, the **Release** workflow starts by itself. It reads `versionName` and
`versionCode` from the tagged commit, checks that every APK carries the same values, signs the APKs
without rebuilding them and publishes them in the Releases tab.

Tags must look like `v1.2.3`. The version may not go down, and an existing tag is never reused. To
release an existing tag again, run **Release** by hand with the tag (and optionally a CI run id).

Required secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`.

## Bootstrap

The first start downloads the base system (bash, coreutils, apt) from the TP releases. The app
targets API 28 on purpose, because Android 10 and later block running binaries from the app data
directory for newer targets.

## Docs

for more documentation, open [documentations](./DOCS.md) for more info

## Licensed

the app uses [GNUv3.0](./LICENSE)

## Support

support us by forking or stars this repo