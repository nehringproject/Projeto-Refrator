plugins {
    id("com.android.library")
}

android {
    namespace = "dev.agentworkbench.runner.safe"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
}
