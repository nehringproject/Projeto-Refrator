plugins {
    id("com.android.library")
    id("com.chaquo.python")
}

android {
    namespace = "dev.agentworkbench.pythonruntime"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}

val bundledBuildPython = rootProject.layout.projectDirectory
    .file("work/toolchain/python313/python.exe")
    .asFile
val chaquopyBuildPython = providers.gradleProperty("chaquopy.buildPython").orNull
    ?: System.getenv("CHAQUOPY_BUILD_PYTHON")
    ?: bundledBuildPython.takeIf { it.isFile }?.absolutePath
    ?: "python3"

chaquopy {
    defaultConfig {
        version = "3.13"
        // Portável entre checkouts e máquinas de build: sem caminho de usuário gravado no fonte.
        buildPython(chaquopyBuildPython)
        pip {
            // The Android worker invokes pip as a Python module because an
            // embedded Android interpreter has no standalone `python` executable to spawn.
            install("pip==25.3")
            // pydantic-core has no upstream Android wheel. The two wheels in this directory are
            // reproducible cross-builds of the unmodified 2.27.2 source for CPython 3.13. Pip
            // selects the matching ABI, so ARM64 never loads the emulator's x86_64 artifact.
            options("--find-links", layout.projectDirectory.dir("wheels").asFile.absolutePath)
            install("pydantic==2.10.6")
            // LiteLLM 1.90.6 imports yaml from its dotprompt integration but omits PyYAML from
            // the base wheel metadata. Pin it explicitly so a cold Android import is reliable.
            install("pyyaml==6.0.3")
            install("packaging==26.0")
            // Newer jsonschema releases pull rpds-py, another native dependency without Android
            // wheels. 4.17.3 satisfies LiteLLM's declared range and remains pure Python here.
            install("jsonschema==4.17.3")
            // fastuuid is only a performance accelerator, but its Rust wheel isn't published for
            // Android. This audited compatibility package exposes the stdlib uuid API expected by
            // LiteLLM while preserving cryptographic randomness through Android's getrandom.
            install("./fastuuid-android")
            // The upstream tiktoken Rust wheel also has no Android build. This conservative byte
            // tokenizer preserves encode/decode behavior and intentionally overestimates usage.
            install("./tiktoken-android")
            install("./tokenizers-android")
            install("./jiter-android")
            install(
                "https://files.pythonhosted.org/packages/fb/6e/" +
                    "e9823e0b0e613e44fa8d8abb5b928f37cc42495ffb6c192c36be646348e7/" +
                    "litellm-1.90.6-py3-none-any.whl#sha256=" +
                    "8c70c9b6150a98264bee481d371a63b1daca1f2a80cee032ba04dd443aec7beb",
            )
        }
    }
}

// Chaquopy 17 validates the selected host Python while Gradle builds the task graph. Gradle 9's
// configuration cache cannot serialize that external-process lookup.
tasks.configureEach {
    notCompatibleWithConfigurationCache("Chaquopy 17 resolves the CPython build environment dynamically")
}

// Chaquopy registers this generated consumer file even when the project has no
// Python-to-Java proxy classes. AGP 9 validates the path before merging it.
val ensurePythonConsumerRules = tasks.register("ensurePythonConsumerRules") {
    val output = layout.buildDirectory.file("python/proguard-rules.pro")
    outputs.file(output)
    doLast {
        val rules = output.get().asFile
        rules.parentFile.mkdirs()
        if (!rules.exists()) {
            rules.writeText("# No Python proxy classes in this module.\n")
        }
    }
}

tasks.configureEach {
    if (
        name.endsWith("ConsumerProguardFiles") ||
        name.contains("lint", ignoreCase = true)
    ) {
        dependsOn(ensurePythonConsumerRules)
    }
}
