plugins {
    alias(libs.plugins.android.library)
}

// The car's screen: the session the car's host binds to, its screens and their templates. It knows
// the app through :core-nav and the car only through the Car App Library; no Mapbox type reaches it
// (DECISIONS 12). The map on the car's surface is made for it by the engine.
android {
    namespace = "io.github.purplepalm.custommaps.car"
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

    // The session, the screens and the templates are the library's own types.
    api(libs.androidx.car.app)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The car's words, which CarMessagesTest reads from the resources, and the phone's, which
// CarDriveWordsTest holds them to: as inputs of the test task, a change to them runs the tests again
// instead of taking their result from the build cache.
val carResources = layout.projectDirectory.dir("src/main/res")
val phoneStrings = rootProject.layout.projectDirectory.file("app/src/main/res/values/strings.xml")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    inputs.dir(carResources).withPropertyName("carResources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(phoneStrings).withPropertyName("phoneStrings").withPathSensitivity(PathSensitivity.RELATIVE)
}
