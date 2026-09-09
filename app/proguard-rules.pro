# R8 / ProGuard rules for release builds.
#
# R8 is currently disabled (buildTypes.release { optimization { enable = false } }).
# When it's turned on (see docs/security.md, owner action #2), OkHttp, Coil,
# Firebase and kotlinx all ship their own consumer rules, and the app uses
# org.json (no reflective serialization), so little to nothing should be needed
# here — but smoke-test the shrunk build end to end and add keeps for anything
# that breaks at runtime.
#
# Keep line numbers for readable crash reports (Crashlytics uploads the mapping):
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
