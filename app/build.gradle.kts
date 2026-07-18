plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.andreiefimov.torrentwebui"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.andreiefimov.torrentwebui"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            isMinifyEnabled = false
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // Fix duplicate META-INF files from Netty/Ktor dependencies.
    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/INDEX.TXT",
                "META-INF/io.netty.versions.properties"
            )
        }
    }

    // NDK + CMake: arm64-v8a only
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "29.0.14206865"
}

// Pre-build: automatically copy SvelteKit build output to assets/www/ before Gradle assembles the APK.
tasks.register("copyWebAssets", Exec::class) {
    group = "build"
    description = "Runs npm run copy-assets to build SvelteKit and deploy to assets/www/"
    workingDir = rootProject.file("web")
    commandLine("npm", "run", "copy-assets")
}

// Wire copyWebAssets to run before any APK assembly task.
tasks.named("preBuild").configure {
    dependsOn(tasks.named("copyWebAssets"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.activity)

    // ViewModel + Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
