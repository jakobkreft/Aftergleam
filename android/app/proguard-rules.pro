# WorkManager instantiates workers by class name, so R8 cannot see the constructor being
# used and will otherwise strip or rename it. A stripped worker fails silently at 05:00,
# which is the worst possible place to find out.
-keep class si.jakobkreft.aftergleam.work.** { *; }

# The activity is named in the manifest and in the notification's PendingIntent.
-keep class si.jakobkreft.aftergleam.MainActivity { *; }

# XmlPullParser is resolved through the platform factory rather than a direct reference.
-dontwarn org.xmlpull.v1.**

# R8 warns about desugared library internals referenced by kotlinx.coroutines.
-dontwarn java.lang.invoke.**

# Keep the line numbers so a stack trace from a release build is worth reading, and hide the
# original file names, which are the only part worth obfuscating here.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
