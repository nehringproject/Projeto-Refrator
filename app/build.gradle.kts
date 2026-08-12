import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = providers.environmentVariable("REFRATOR_KEYSTORE").orNull
val releaseKeystorePassword = providers.environmentVariable("REFRATOR_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("REFRATOR_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("REFRATOR_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "dev.agentworkbench"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "dev.agentworkbench"
        minSdk = 28
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("refratorRelease") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("dev") {
            dimension = "distribution"
            applicationId = "dev.agentworkbench.power"
            versionNameSuffix = "-dev"
            buildConfigField("String", "DISTRIBUTION", "\"dev\"")
            buildConfigField("boolean", "FULL_CAPABILITIES", "true")
            buildConfigField("boolean", "DEVELOPER_BUILD", "true")
            buildConfigField("String", "RELEASE_CHANNEL", "\"dev\"")
            resValue("string", "app_name", "Refrator Dev")
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        create("public") {
            dimension = "distribution"
            applicationId = "com.nehringproject.refrator"
            buildConfigField("String", "DISTRIBUTION", "\"public\"")
            buildConfigField("boolean", "FULL_CAPABILITIES", "true")
            buildConfigField("boolean", "DEVELOPER_BUILD", "false")
            buildConfigField("String", "RELEASE_CHANNEL", "\"public\"")
            resValue("string", "app_name", "Refrator")
            ndk {
                abiFilters += "arm64-v8a"
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("refratorRelease")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
        resValues = true
    }

    packaging {
        // llama.cpp loads GGML backends dynamically from nativeLibraryDir.
        // Modern in-APK loading leaves that directory empty, so extract the
        // libraries on install on every supported Android version.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    sourceSets {
        listOf("dev", "public").forEach { variant ->
            getByName(variant).apply {
                assets.directories.add(rootProject.file("runtime-assets").absolutePath)
            }
        }
    }

}

val verifyRuntimeBootstrap = tasks.register("verifyRuntimeBootstrap") {
    val bootstrap = rootProject.file("runtime-assets/bootstrap-aarch64.zip")
    inputs.file(bootstrap)
    doLast {
        require(bootstrap.isFile) { "Runtime bootstrap ausente: ${bootstrap.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        bootstrap.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }
        require(actual == "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3") {
            "SHA-256 do runtime bootstrap divergente: $actual"
        }
    }
}

tasks.configureEach {
    // Debug builds remain reproducible from source without redistributing the
    // separately licensed command-line bundle. Release builds require the
    // reviewed archive and verify its digest before packaging.
    if (name == "preDevReleaseBuild" || name == "prePublicReleaseBuild") {
        dependsOn(verifyRuntimeBootstrap)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":local-llm"))
    implementation(project(":provider-http"))
    implementation(project(":runner-safe"))
    listOf("devImplementation", "publicImplementation").forEach { configuration ->
        add(configuration, project(":runner-power"))
        add(configuration, project(":python-runtime"))
        add(configuration, libs.shizuku.api)
        add(configuration, libs.shizuku.provider)
    }

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.jgit)
    implementation(libs.tesseract)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(kotlin("test-junit"))
    testImplementation("org.json:json:20260719")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
