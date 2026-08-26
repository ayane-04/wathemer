import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.wathemer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wathemer.app"
        // minSdk 31: RenderEffect.createBlurEffect is the wallpaper blur path; below 31 would need a software blur.
        minSdk = 31
        targetSdk = 36
        // Bump on every build that leaves this machine, or a log cannot be tied to a build.
        versionCode = 182
        versionName = "0.9.2"

        ndk {
            // arm64 only: no 32-bit Android 12 devices exist, and emulators cannot run an Xposed module.
            // APK bulk is DEX, not natives; R8 stays off until someone writes the reflective keep rules.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Credentials live in the user-level ~/.gradle/gradle.properties; a machine without them builds unsigned rather than failing.
    signingConfigs {
        create("release") {
            val storePath = project.findProperty("WATHEMER_STORE_FILE") as String?
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = project.findProperty("WATHEMER_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("WATHEMER_KEY_ALIAS") as String?
                keyPassword = project.findProperty("WATHEMER_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (project.findProperty("WATHEMER_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }

    packaging {
        resources {
            // Build-tool metadata nobody reads at runtime; DebugProbesKt also costs the IDE's coroutine debugger view.
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.version",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**"
            )
        }
    }
}

dependencies {
    // Xposed (provided at runtime by LSPosed framework)
    compileOnly(libs.libxposed.legacy)

    // DexKit resolves obfuscated WhatsApp classes; every query is validated against this exact AAR, do not swap in the Maven 2.x.
    implementation(files("libs/dexkit-android.aar"))

    // The dexkit AAR does not bundle FlatBuffers and files() pulls no transitives; without this every DexKit query throws NoClassDefFoundError.
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26")

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM-managed)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    // No ui-tooling-preview: it ships in release and nothing here uses @Preview. ui-tooling is debug-only and backs the Layout Inspector.
    debugImplementation(libs.compose.ui.tooling)

    // Material (XML theme parent for settings activity)
    implementation(libs.material)

    // UCrop crops the wallpaper; JitPack-resolved via the repo in settings.gradle.kts.
    // OkHttp/Okio excluded: UCrop uses them only to download http Uris and the picker only hands it local ones.
    // ART resolves a missing class when the referencing method RUNS, so a remote Uri would throw NoClassDefFoundError; delete the two excludes to revert.
    implementation(libs.ucrop) {
        exclude(group = "com.squareup.okhttp3")
        exclude(group = "com.squareup.okio")
    }
}
