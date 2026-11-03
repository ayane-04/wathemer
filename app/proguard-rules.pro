# WaThemer: Xposed-safe ProGuard rules.
# Release runs R8 to shrink: nothing renamed, the optimiser off, every module class kept.

# Never obfuscate: a hook's Class.forName literal naming a host class would be rewritten to our renamed copy of it.
-dontobfuscate
# No optimiser: it has no shipping precedent over DexKit, FlatBuffers and Compose, and it blurs stack-trace lines.
-dontoptimize
-keepattributes SourceFile,LineNumberTable

# UCrop's downloader, excluded in build.gradle.kts: the only classes R8 finds missing.
-dontwarn okhttp3.**
-dontwarn okio.**

# A blanket keep of every module class; any narrower rule must still keep ModernEntry, which the framework loads by name.
-keepclasseswithmembers class com.wathemer.app.** {
    *;
}
-keepclasseswithmembernames class com.wathemer.app.**

# Modern module contract: the entry list is rewritten on obfuscation and the service client is reflective
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep class io.github.libxposed.** { *; }
