import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Host tools for the native build (machine-specific): Python embeds the OpenCL kernels, a host
// C/C++ compiler builds the Vulkan shader generator. Taken from local.properties
// (edge.python / edge.hostCc / edge.hostCxx) or else found on PATH.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun hostTool(key: String, vararg names: String): String? =
    localProps.getProperty(key) ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).firstNotNullOfOrNull { dir ->
        names.flatMap { listOf(it, "$it.exe") }.map { File(dir, it) }.firstOrNull { it.isFile && it.length() > 0 }?.absolutePath
    }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "node.nveloper.edge"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "node.nveloper.edge"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // arm64 for real phones, x86_64 for the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // One shared C++ runtime for libllama, the ggml backends and the JNI bridge.
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                // llama.cpp/Vulkan compile units are memory hungry; cap parallel compiles per ABI
                // so a full build fits on machines with ~8–12 GB of RAM (override: edge.nativeJobs).
                val jobs = localProps.getProperty("edge.nativeJobs") ?: "2"
                arguments += listOf("-DCMAKE_JOB_POOLS=compile=$jobs;link=1", "-DCMAKE_JOB_POOL_COMPILE=compile", "-DCMAKE_JOB_POOL_LINK=link")
                hostTool("edge.python", "python3", "python")?.let { arguments += "-DPython3_EXECUTABLE=$it" }
                hostTool("edge.hostCc", "gcc", "clang", "cc")?.let { arguments += "-DEDGE_HOST_CC=$it" }
                hostTool("edge.hostCxx", "g++", "clang++", "c++")?.let { arguments += "-DEDGE_HOST_CXX=$it" }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // UI follows the system language; also lists the app under Settings → App languages.
    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        jniLibs {
            // ggml loads its backends (libggml-vulkan.so, libggml-cpu-*.so, …) by scanning
            // nativeLibraryDir, so the libraries must be extracted from the APK.
            useLegacyPackaging = true
            // Link-time stub only: at runtime the vendor's libOpenCL.so must be used.
            excludes += "**/libOpenCL.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.litertlm.android)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
}
