# WaThemer: Xposed-safe ProGuard rules.
# isMinifyEnabled is false; kept for the day R8 is turned on.

-dontwarn *

# R class accessed via reflection (Xposed resource injection)
-keep class com.wathemer.app.R { *; }
-keep class com.wathemer.app.R$* { *; }
-keepclassmembers class com.wathemer.app.R$* {
    public static <fields>;
}

# Keep all hook entry classes (must not be stripped or renamed; LSPosed loads by FQN)
-keepclasseswithmembers class com.wathemer.app.** {
    *;
}
-keepclasseswithmembernames class com.wathemer.app.**

# Standard Xposed API: keep its annotations
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }
