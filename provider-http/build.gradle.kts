plugins {
    id("com.android.library")
}

android {
    namespace = "dev.agentworkbench.provider.http"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
    testImplementation("org.json:json:20260719")
}
