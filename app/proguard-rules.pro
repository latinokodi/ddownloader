# R8 / ProGuard rules for TorDownloader Android.
#
# The app deliberately has no reflection-heavy frameworks (no Hilt, no Room codegen,
# no kotlinx.serialization @Serializable classes — JSON is parsed as JsonElement),
# so this file stays small: metadata for meaningful stack traces, plus the usual
# third-party silencing.

# Keep line numbers so crash reports from a minified build stay actionable.
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

# Generic signatures are used by OkHttp/Coil reflection-free but kept for safety.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Compose/Material ship their own consumer rules; nothing extra needed.
# Keep the instrumentation entry points referenced from XML/tests.
-keep class pro.tordownloader.android.download.DownloadService { *; }
-keep class pro.tordownloader.android.App { *; }
