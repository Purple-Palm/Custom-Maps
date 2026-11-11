import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Commit the build was made from, shown in Settings: the short SHA on CI, "local" otherwise.
val buildRevision: String = providers.environmentVariable("GITHUB_SHA").map { it.take(7) }.getOrElse("local")

val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull?.let { load(it.reader()) }
}

/** A keystore file and the alias and passwords that open the key in it. */
class SigningKey(val file: String, val storePassword: String, val alias: String, val keyPassword: String)

/**
 * The key whose four values start with [prefix]: `<prefix>_KEYSTORE_FILE`, `_KEYSTORE_PASSWORD`,
 * `_KEY_ALIAS` and `_KEY_PASSWORD`, each from an environment variable (CI secrets) or local.properties.
 * All four or none: null when none is set, and a build with only some of them fails with a message
 * that names the missing ones, never a value.
 */
fun signingKey(prefix: String, title: String): SigningKey? {
    val names = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").map { "${prefix}_$it" }
    val values = names.associateWith { name ->
        providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
            ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
    }
    val missing = values.filterValues { it == null }.keys
    check(missing.isEmpty() || missing.size == names.size) {
        "$title is only partly configured; missing: ${missing.joinToString()}"
    }
    if (missing.isNotEmpty()) return null
    val (file, storePassword, alias, keyPassword) = names.map { values.getValue(it)!! }
    return SigningKey(file, storePassword, alias, keyPassword)
}

// Optional shared key for debug builds, so a newer build (from CI or Android Studio) installs over the
// installed one.
val debugKey = signingKey("DEBUG", "Shared debug signing key")

// The upload key for Google Play. Release builds are signed with it when it is configured and are
// unsigned otherwise; Google Play signs what it delivers with its own app signing key.
val releaseKey = signingKey("RELEASE", "Release signing key")

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
        fun add(name: String, key: SigningKey) = create(name) {
            storeFile = file(key.file)
            storePassword = key.storePassword
            keyAlias = key.alias
            keyPassword = key.keyPassword
        }
        debugKey?.let { add("sharedDebug", it) }
        releaseKey?.let { add("release", it) }
    }

    buildTypes {
        debug {
            // Lets a debug build live next to the Play build, so neither has to be uninstalled.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (debugKey != null) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
        }
        release {
            if (releaseKey != null) {
                signingConfig = signingConfigs.getByName("release")
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
