import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.detekt)
}

val libztAarByName = file("libs/libzt.aar")
val libztAarCandidates = fileTree("libs") {
    include("*.aar")
}

fun resolveLibztAarOrNull(): File? {
    if (libztAarByName.exists()) return libztAarByName
    val matching = libztAarCandidates.files
        .filter { it.name.contains("libzt", ignoreCase = true) || it.name.contains("zerotier", ignoreCase = true) }
        .sortedBy { it.name.lowercase() }
    return matching.firstOrNull()
}

android {
    namespace = "com.example.storagenas"
    ndkVersion = "30.0.14904198"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.storagenas"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    kapt(libs.androidx.hilt.compiler)
    implementation(libs.sshj)
    val resolvedLibztAar = resolveLibztAarOrNull()
    if (resolvedLibztAar != null) {
        implementation(files(resolvedLibztAar))
    } else {
        logger.warn("ZeroTier libzt AAR not found in app/libs/. Embedded features will be disabled.")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kapt {
    correctErrorTypes = true
}

tasks.register("verifyLibztAar") {
    doLast {
        val resolvedLibztAar = resolveLibztAarOrNull()
        if (resolvedLibztAar == null) {
            val availableAars = libztAarCandidates.files.map { it.name }.sorted()
            logger.warn(
                "ZeroTier SDK AAR missing from app/libs/. " +
                    "Embedded ZeroTier features will be disabled at runtime. " +
                    "Found in libs/: ${if (availableAars.isEmpty()) "(none)" else availableAars.joinToString()}"
            )
            return@doLast
        }

        // Basic archive sanity check to catch accidental non-AAR files renamed to .aar.
        ZipFile(resolvedLibztAar).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") == null) {
                logger.warn("WARNING: Invalid AAR: ${resolvedLibztAar.absolutePath}. AndroidManifest.xml missing.")
            }
            if (zip.getEntry("classes.jar") == null) {
                logger.warn("WARNING: Invalid AAR: ${resolvedLibztAar.absolutePath}. classes.jar missing.")
            }
        }
    }
}

tasks.matching {
    it.name.startsWith("assemble") ||
        it.name.startsWith("bundle") ||
        it.name.startsWith("install")
}.configureEach {
    dependsOn("verifyLibztAar")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    ignoreFailures = true
}
