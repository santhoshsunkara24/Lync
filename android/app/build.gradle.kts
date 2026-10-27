import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Nothing's Glyph SDK can't be redistributed, so it's optional: with the file in libs/ the Glyph
// feature is built in (src/glyph); without it, a stub (src/noglyph) hides it. See libs/README.md.
val glyphSdk = file("libs/glyph-matrix-sdk-2.0.aar")

android {
    namespace = "app.wallhop"
    compileSdk = 37

    defaultConfig {
        // Permanent: Play and Android identify the app by this, so it can never change after release.
        applicationId = "com.santhoshsunkara.lync"
        // -Plync.devId=app.wallhop builds over the original development install and keeps its pairing.
        (findProperty("lync.devId") as String?)?.let { applicationId = it }
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.0"
    }

    // The upload key lives outside the repo. Point `lync.signing` (in ~/.gradle/gradle.properties)
    // at a properties file with storeFile, storePassword, keyAlias and keyPassword. Without it,
    // release builds come out unsigned.
    val signing = (findProperty("lync.signing") as String?)
        ?.let(::file)?.takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (signing != null) create("upload") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "STRESS_HOOKS", "true")
            buildConfigField("boolean", "PREVIEW", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "STRESS_HOOKS", "false")
            buildConfigField("boolean", "PREVIEW", "false")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
        // Release code (R8, not debuggable) signed with the debug key, so it installs over a
        // debug build and keeps the pairing. Has the test hooks the stress test needs.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "STRESS_HOOKS", "true")
            buildConfigField("boolean", "PREVIEW", "false")
        }
        // A look-only build to share: installs beside the real app as "Lync Preview" and always
        // opens with sample data, so it needs no Mac. No test hooks.
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            buildConfigField("boolean", "STRESS_HOOKS", "false")
            buildConfigField("boolean", "PREVIEW", "true")
        }
    }

    sourceSets["main"].kotlin.directories += if (glyphSdk.exists()) "src/glyph/java" else "src/noglyph/java"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Installs the Compose libraries' baseline profiles on sideloaded APKs, so they start compiled.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // QR pairing: CameraX for the preview, ZXing (Apache 2.0, no Google services) to decode.
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.crypto.tink:tink-android:1.15.0")
    // Continuous (squircle) corners.
    implementation("io.github.kyant0:shapes:1.2.1")
    if (glyphSdk.exists()) implementation(files(glyphSdk))

    testImplementation("junit:junit:4.13.2")
}
