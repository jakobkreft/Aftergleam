# Development

## Build

Any JDK 17 or newer will start the build. Gradle provisions the JDK it runs on by itself,
from `android/gradle/gradle-daemon-jvm.properties`.

```sh
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug
```

If the daemon reports "JDK home directory does not exist" after an Android Studio update,
run `./gradlew --stop`.

### Release builds

```sh
./gradlew assembleRelease
```

Without signing settings this produces `app-release-unsigned.apk`, which is what F-Droid
builds from. To sign, set these in the environment or in `~/.gradle/gradle.properties`.
Never commit them.

| Environment | Gradle property |
|---|---|
| `AFTERGLEAM_KEYSTORE` | `aftergleam.keystore` |
| `AFTERGLEAM_KEYSTORE_PASSWORD` | `aftergleam.keystorePassword` |
| `AFTERGLEAM_KEY_ALIAS` | `aftergleam.keyAlias` |
| `AFTERGLEAM_KEY_PASSWORD` | `aftergleam.keyPassword` |

The build leaves the git hash and the dependency blob out of the APK so that two checkouts of
the same commit produce the same bytes.

## Layout

```
android/app/src/main/java/si/jakobkreft/aftergleam/
  data/   sources (arXiv, bioRxiv, medRxiv, OSF, ChemRxiv), storage, subjects, signals
  rank/   TF-IDF, logistic regression, digest composition, explanations
  ui/     Compose screens and the view model
  work/   the nightly digest, metadata refresh and reading reminder
prototype/  the Python harness the ranking choices were measured with
docs/       design notes and the measurements behind them
```

## Decisions worth knowing

Each of these was measured rather than assumed. The reasoning is in
[`05-algorithm.md`](05-algorithm.md).

**TF-IDF, not an embedding model.** On a real 38 paper library, TF-IDF placed 21 of 24
held-out papers in the top ten of 301 candidates, against 19 of 24 for a quantised MiniLM.
The difference is not significant at that size, so the cheaper model wins: no download, no
native libraries, and explanations that quote real words.

**Everything is local.** The model is retrained on the device from the reader's reactions.
The only network traffic is fetching papers and the public Hugging Face daily list.

**Venue multiplies interest, it is not added to it.** Added, a conference acceptance
outweighed predicted interest and the digest became a list of recently accepted papers. As a
multiplier, a well published paper on a topic the reader dislikes still ranks low.

**Only the servers a reader needs are contacted.** A computer scientist never waits on the
servers for law or psychology. `Fetcher` decides this in one place.

**arXiv announces once per weekday.** Fetching again between announcements returns the same
papers, so re-ranking is local and fetching is skipped for ten minutes after a fetch unless
a new subject was added.

**All arXiv requests share one rate limiter.** arXiv allows one request every three seconds.
Two callers pacing themselves separately exceeded it together.

## Testing on a device

The screen must be unlocked, or screenshots come back black and UI dumps come back empty.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb exec-out run-as si.jakobkreft.aftergleam cat databases/aftergleam.db > app.db
```

Take a copy of the app's data before clearing it, and keep the copy outside any temporary
directory. `run-as` can read the data directory but cannot write back into it on current
Android, so a copy is for reference rather than for restoring.

Check layouts under three button navigation as well as gestures. The button bar is taller
than the gesture pill and covers content the pill does not.

```sh
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural
```

Horizontal swipes sent with `adb shell input swipe` must start away from the screen edges,
where the system back gesture takes them.
