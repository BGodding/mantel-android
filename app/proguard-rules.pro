# R8 / ProGuard rules for release builds.
#
# R8 is enabled for release builds (buildTypes.release { optimization { enable = true } });
# CI builds assembleRelease to catch shrinker breakage. OkHttp, Coil, Firebase and kotlinx
# all ship their own consumer rules, and the app uses org.json (no reflective
# serialization), so little to nothing is needed here — but smoke-test the shrunk build end
# to end and add keeps for anything that breaks at runtime.
#
# R8 renames classes, so `Class.simpleName` is meaningless in release. Exception class names
# are put into crash-report messages on purpose (e.g. "cause=SocketTimeoutException"), so keep
# the names of every Throwable; anything else sent to telemetry must use an explicit label.
-keepnames class * extends java.lang.Throwable
#
# Keep line numbers for readable crash reports (Crashlytics uploads the mapping):
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
