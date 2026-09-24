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
        versionCode = 238
        versionName = "1.0.8"

        ndk {
            // arm64 only: 32-bit-only Android 12 phones effectively do not exist, and emulators are not a target.
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
            // R8 stays off until someone writes the reflective keep rules.
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

    // Modern module contract; the hook side still calls the legacy API, which 101 permits and 102 blocks.
    compileOnly(libs.libxposed.api)
    // Settings-side service client; bundled, it is how the app reaches the framework's preference store.
    implementation(libs.libxposed.service)

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

    // OkHttp/Okio excluded: UCrop needs them only to download an http Uri, and both croppers hand it a local one.
    // ART resolves the missing class when that path runs, so a remote Uri would throw NoClassDefFoundError; delete the excludes to revert.
    implementation(libs.ucrop) {
        exclude(group = "com.squareup.okhttp3")
        exclude(group = "com.squareup.okio")
    }
}
