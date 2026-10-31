# Development

## Build and test

Any JDK 17 or newer starts the build. Gradle fetches the JDK it runs on.

```sh
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

If Android Studio is open, stop the daemon after building from a terminal (`./gradlew --stop`).
Otherwise Android Studio may pick up that daemon and fail to sync.

## Signing

`./gradlew assembleRelease` gives an unsigned APK, which is what F-Droid builds. To sign, set
these in the environment or in `~/.gradle/gradle.properties`, never in the repository:

| Environment | Gradle property |
|---|---|
| `AFTERGLEAM_KEYSTORE` | `aftergleam.keystore` |
| `AFTERGLEAM_KEYSTORE_PASSWORD` | `aftergleam.keystorePassword` |
| `AFTERGLEAM_KEY_ALIAS` | `aftergleam.keyAlias` |
| `AFTERGLEAM_KEY_PASSWORD` | `aftergleam.keyPassword` |

## Releasing

F-Droid rebuilds each tag and only ships it if the result matches the APK on GitHub, so always
build from the tag.

1. Raise `versionCode` and `versionName` in `android/app/build.gradle.kts`, and write
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (under 500 characters).
2. Commit, then `git tag -a vX.Y.Z -m "Aftergleam X.Y.Z"` and push the tag.
3. Build from a clean clone of the tag, with signing set and `ANDROID_HOME` exported:
   ```sh
   git clone --branch vX.Y.Z . /tmp/aftergleam-release
   cd /tmp/aftergleam-release/android && ./gradlew assembleRelease
   ```
4. `apksigner verify --print-certs` must show the SHA-256 in F-Droid's `AllowedAPKSigningKeys`.
5. Attach it to the GitHub release as `aftergleam-X.Y.Z.apk`.

## Layout

```
android/app/src/main/java/si/jakobkreft/aftergleam/
  data/   servers, storage, subjects, signals
  rank/   TF-IDF, logistic regression, building the digest
  ui/     screens and the view model
  work/   the morning fetch, the daily note, keyword alerts and their alarms
prototype/  Python scripts used to measure ranking choices
```

## Testing on a phone

The screen must be unlocked, or screenshots come back black. Check layouts with three-button
navigation as well as gestures; the button bar covers more.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb exec-out run-as si.jakobkreft.aftergleam cat databases/aftergleam.db > app.db
```
