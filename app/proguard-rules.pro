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

# A precaution: nothing reflects on R; the hooks look module resources up by name.
-keep class com.wathemer.app.R { *; }
-keep class com.wathemer.app.R$* { *; }
-keepclassmembers class com.wathemer.app.R$* {
    public static <fields>;
}

# A blanket keep of every module class; any narrower rule must still keep ModernEntry, which the framework loads by name.
-keepclasseswithmembers class com.wathemer.app.** {
    *;
}
-keepclasseswithmembernames class com.wathemer.app.**

# The legacy Xposed API is compileOnly and provided at runtime; kept whole as a precaution.
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }

# Modern module contract: the entry list is rewritten on obfuscation and the service client is reflective
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep class io.github.libxposed.** { *; }
