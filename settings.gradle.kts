pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content {
                includeGroup("cz.adaptech.tesseract4android")
                // Apache-2.0 terminal-emulator/terminal-view only; the GPL Termux app is not used.
                includeGroup("com.github.termux.termux-app")
            }
        }
    }
}

rootProject.name = "Refrator"

include(
    ":app",
    ":core",
    ":local-llm",
    ":python-runtime",
    ":provider-http",
    ":runner-safe",
    ":runner-power",
)

project(":local-llm").projectDir = file("local-llm")
