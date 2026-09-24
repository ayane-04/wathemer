# WaThemer: Xposed-safe ProGuard rules.
# isMinifyEnabled is false; kept for the day R8 is turned on.

-dontwarn *

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
