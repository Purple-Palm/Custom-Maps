plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.purplepalm.custommaps.engine.mapbox"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core-nav"))

    // Mapbox stays an implementation detail: no Mapbox type is visible to other modules.
    implementation(libs.mapbox.navigation)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
