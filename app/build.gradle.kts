import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Commit the build was made from, shown in Settings: the short SHA on CI, "local" otherwise.
val buildRevision: String = providers.environmentVariable("GITHUB_SHA").map { it.take(7) }.getOrElse("local")

// Optional shared key for debug builds, so a newer build (from CI or Android Studio) installs over the
// installed one. Values come from environment variables (CI secrets) or local.properties.
val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull?.let { load(it.reader()) }
}
val debugSigning: Map<String, String?> =
    listOf("DEBUG_KEYSTORE_FILE", "DEBUG_KEYSTORE_PASSWORD", "DEBUG_KEY_ALIAS", "DEBUG_KEY_PASSWORD")
        .associateWith { name ->
            providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
                ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        }
val missingDebugSigning = debugSigning.filterValues { it == null }.keys
check(missingDebugSigning.isEmpty() || missingDebugSigning.size == debugSigning.size) {
    "Shared debug signing key is only partly configured; missing: ${missingDebugSigning.joinToString()}"
}
val useSharedDebugKey = missingDebugSigning.isEmpty()

android {
    namespace = "io.github.purplepalm.custommaps"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.purplepalm.custommaps"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        resValue("string", "app_version", versionName ?: "")
        resValue("string", "build_revision", buildRevision)

        ndk {
            // Phones run arm64; x86_64 keeps the emulator working. CI builds arm64 only
            // (-Pcustommaps.abis=arm64-v8a) to halve the APK the phone downloads.
            abiFilters += providers.gradleProperty("custommaps.abis")
                .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
                .getOrElse(listOf("arm64-v8a", "x86_64"))
        }
    }

    signingConfigs {
        if (useSharedDebugKey) {
            create("sharedDebug") {
                storeFile = file(debugSigning.getValue("DEBUG_KEYSTORE_FILE")!!)
                storePassword = debugSigning.getValue("DEBUG_KEYSTORE_PASSWORD")
                keyAlias = debugSigning.getValue("DEBUG_KEY_ALIAS")
                keyPassword = debugSigning.getValue("DEBUG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Lets a debug build live next to the Play build, so neither has to be uninstalled.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (useSharedDebugKey) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
    }

    lint {
        // Also analyze :core-nav and :engine-mapbox with this app's minSdk (NewApi and friends).
        checkDependencies = true
    }
}

dependencies {
    implementation(project(":core-nav"))
    implementation(project(":engine-mapbox"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
