# Aftergleam, Android app

Offline arXiv reader. Ten papers a day, ranked by what you star, with no account and no
server. Nothing about what you read leaves the device.

## Build

Android Studio's bundled JBR is the only usable JDK on this machine; the system `java` is
a JRE and will fail.

```sh
export JAVA_HOME=/var/lib/flatpak/app/com.google.AndroidStudio/x86_64/stable/active/files/extra/jbr
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

If the Gradle daemon reports "JDK home directory does not exist", Android Studio updated and
moved the `active` symlink: run `./gradlew --stop`.

## Shape of the code

```
data/    Paper, ArxivApi (Atom), Venue (quality signal), Db (SQLite), Prefs
rank/    Tfidf, LogReg, Ranker (digest composition)
ui/      FeedViewModel, Screens
```

Six decisions worth knowing, all of them measured rather than assumed:

**No embedder.** Leave-one-out on a real 38-paper library put 21 of 24 held-out cs.CV papers
in the top ten of 301 candidates using TF-IDF, against 19 of 24 for a quantised MiniLM. The
difference is not statistically resolved, so the cheaper model wins: no model download, no
native libraries, no 16 KB page-alignment concern, and a "why" chip that quotes real words.
See `docs/03-e1-results.md`.

**Quality comes from arXiv's own metadata, not citations.** OpenAlex reports zero citations
for 91% of cs.LG preprints even eighteen months on, because preprint records carry no
citation edges and the ML venues are barely in the graph. The `comments` field, where authors
write "Accepted at ECCV 2026", covers about 30% of papers at six to twelve months and costs
no extra request. See `docs/01-findings.md`.

**Empty days are normal.** arXiv does not announce at weekends or on US holidays, and the RSS
feed returns a valid document with zero items on those days. The feed says so rather than
showing an error.

**Interest is a rating, not a star.** Each card shows what the model predicted and a slider
seeded at that value, so the gesture is "correct the machine" rather than "fill in a form".
The buttons are shortcuts to 0.9 and 0.1. Cross-entropy takes soft targets directly, so this
needed no new model. "Save" is orthogonal and trains nothing.

**Quality multiplies interest, it is not added to it.** Additively, venue swamped the ranking:
nine of the top ten cards were placed by venue rather than by predicted interest, making the
digest "recently accepted papers" instead of "papers you will like". As a multiplier that count
went to zero of twenty-five. `RankCoreTest` asserts that a NeurIPS paper on a topic you dislike
still loses to an unpublished one on a topic you like.

**Re-ranking and fetching are different operations.** arXiv announces once per weekday at 20:00
US Eastern; measured, the newest cs.CV submission stayed put across a full day of polling. So
re-ranking is local, instant and works with the radio off, while fetching is throttled to six
hours.

## Testing on device

The screen must be unlocked or screenshots come back black and UI dumps come back empty.

```sh
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell run-as si.jakobkreft.aftergleam cat databases/aftergleam.db > /tmp/a.db
```

Check the layout under three-button navigation as well as gestures; the bar is about 48dp
and covers content that a gesture pill does not:

```sh
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural
```
