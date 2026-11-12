/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.navigation.safeargs")
    id("de.undercouch.download")
}

android {
    compileSdk = 34
    namespace = "com.vyze.app"

    signingConfigs {
        // Release signing (audit P1 #7, 2026-11-09): NO debug-keystore
        // fallback — a "release" signed with the debug key MUST NEVER
        // EXIST, not even locally. Without app/release.keystore, any
        // release-packaging task fails fast with the error below.
        // Non-release invocations (debug builds, unit tests, manifest
        // checks) configure normally — the gate only fires when a
        // release artifact is actually requested.
        create("release") {
            val releaseKeystore = file("release.keystore")
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            } else {
                val wantsReleaseArtifact = gradle.startParameter.taskNames.any { task ->
                    task.contains("Release", ignoreCase = true) &&
                        (task.contains("assemble", ignoreCase = true) ||
                            task.contains("bundle", ignoreCase = true) ||
                            task.contains("package", ignoreCase = true))
                }
                if (wantsReleaseArtifact) {
                    throw GradleException(
                        "app/release.keystore is missing — refusing to sign a release build. " +
                            "Create a production keystore at app/release.keystore and set " +
                            "RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD. " +
                            "Signing a release with the debug key is not allowed."
                    )
                }
            }
        }
    }

    defaultConfig {
        applicationId = "com.vyze.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        dataBinding = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }



    androidResources {
        // AAPT DEFLATE-compresses these asset types inside the APK otherwise.
        // Compressed entries cannot be mmap'd, so any code that opens them via
        // openFd() would throw "...it is probably compressed".
        // (litertlm/tflite belong to the on-device VLM engine, not TTS —
        // the sherpa-onnx 'onnx'/'txt' entries were removed with the TTS pivot.)
        noCompress += "litertlm"
        noCompress += "tflite"
    }

    testOptions {
        unitTests {
            // JVM tests exercise the agent runtime (VyzeAgentRuntime), which
            // logs via android.util.Log. Without this, the android.jar stubs
            // throw "not mocked". Returning defaults keeps Log.* a no-op —
            // production code is untouched.
            isReturnDefaultValues = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // ADK transitives (google-genai, google-auth-library, api-common)
            // ship overlapping jar METADATA entries. These are inert manifest
            // files — excluding them has no runtime effect; merging is what
            // fails otherwise (mergeReleaseJavaResource).
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

// Download default models; if you wish to use your own models then
// place them in the "assets" directory and comment out this line.
// Models are bundled in app/src/main/assets/

dependencies {
    // Kotlin
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${rootProject.extra["kotlin_version"]}")
    // kotlinx-coroutines 1.10.1 — built with Kotlin 2.1.20.
    // Required: litertlm-android (≤0.16.1 era) internally calls SendChannel.close(Throwable?)
    // via a synthetic close$default method. Kotlin 2.3.0 + coroutines 1.9.0 have a
    // binary incompatibility where this synthetic is missing at runtime → NoSuchMethodError.
    // 1.10.1 generates the correct synthetic signature for Kotlin 2.x runtimes.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // AppCompat and UI
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Navigation
    val nav_version = "2.7.7"
    implementation("androidx.navigation:navigation-fragment-ktx:$nav_version")
    implementation("androidx.navigation:navigation-ui-ktx:$nav_version")

    // CameraX
    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    // LiteRT-LM runtime for on-device VLM inference.
    // 0.18.0 (brain-swap M0.2, 2026-10-16): MiniCPM-V-4.7-1B bundles carry NO
    // audio tower. 0.16.1 attaches a TF_LITE_AUDIO_ENCODER_HW subgraph on EVERY
    // Conversation creation (audioBackend is non-nullable, no opt-out) →
    // "NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW not found in the model" and every
    // inference dies. 0.18.0 — the version the litert-community conversion was
    // tested on — makes the audio tower optional. Gemma E2B regression: must
    // still load + answer with this runtime (debug selector --es brain gemma).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    // ── Google ADK for Kotlin (ADK migration Phase 0) ─────────────
    // Orchestration layer ONLY. Inert until Phase 3/4: the VyzeAgentModule
    // declares the agent/tool facades but nothing in production calls them
    // yet. The Android artifact REPLACES the JVM one — never add both
    // (per developer.android.com/ai/adk).
    implementation("com.google.adk:google-adk-kotlin-core-android:0.2.0") {
        // DEAD-DEPENDENCY EXCLUSION (offline-claim hardening):
        // ADK transitively pulls com.google.mlkit:genai-prompt (Gemini Nano
        // prompt API), which Vyze never imports — grep of the source tree
        // shows zero com.google.mlkit.genai usage. genai-prompt drags
        // Google's transport-backend-cct telemetry stack (and an
        // android.permission.INTERNET manifest declaration) into every
        // APK. Vyze performs no network communication; this removes one of
        // the two INTERNET riders. The other comes from ML Kit OCR's own
        // transport path — see docs/eval/OFFLINE_GUARANTEE.md.
        exclude(group = "com.google.mlkit", module = "genai-prompt")
    }
    // KSP processor that generates tool descriptors from @Tool-annotated
    // functions (consumed via generatedTools() in Phase 1).
    ksp("com.google.adk:google-adk-kotlin-processor:0.2.0")

    // ADK conflict-resolution FIX: ADK's transitive graph requests the
    // empty marker jar com.google.guava:listenablefuture:9999.0
    // (-empty-to-avoid-conflict-with-guava), which outranks the real
    // listenablefuture:1.0 that CameraX-dependent code
    // (FlashlightManager, CameraSetupDelegate) compiles against. Scope the
    // force to COMPILE classpaths only: runtime must keep ADK's own
    // resolution (empty marker + full guava) — forcing the real jar onto
    // runtime graphs too would duplicate ListenableFuture at packaging
    // (checkReleaseDuplicateClasses) alongside ADK's guava:33.4.0.
    // (If a later ADK phase ever needs full Guava on the compile classpath,
    // add com.google.guava:guava deliberately at that point instead.)
    configurations.matching { it.name.endsWith("CompileClasspath") }.all {
        resolutionStrategy.force("com.google.guava:listenablefuture:1.0")
    }

    configurations.all {
        // R8 FIX: kxml2 (ADK cloud transitive) packages its own copy of
        // org.xmlpull.v1.XmlPullParser, flipping it from a platform library
        // class to a program class — R8 then hard-errors on
        // "library class android.content.res.XmlResourceParser implements
        // program class org.xmlpull.v1.XmlPullParser". Android provides
        // org.xmlpull.v1 in the platform, so the jar is redundant on-device
        // (and absent from the pre-ADK classpath).
        exclude(group = "net.sf.kxml", module = "kxml2")
    }

    // Room (local database for scan history + VLM memory)
    val room_version = "2.7.0"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    // ML Kit Text Recognition — fast on-device OCR (~25MB total)
    // Latin script: English, Malay, Indonesian, Vietnamese, European languages
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Chinese script: Simplified + Traditional Chinese
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // Barcode scanning: 1D/2D — EAN/UPC (banknotes, products), QR, Data Matrix
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Coroutines adapter for ML Kit Task API (provides .await())
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // SavedStateHandle
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.7.0")

    // Lifecycle Service (for AccessibilityService)
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Splash Screen API (AndroidX compat for API 31+ splash)
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    // Real SQLite engine for JVM migration tests (Migration5To6Test).
    // Room migrations are raw SQL strings — only executing them on a real
    // engine catches syntax errors like the G2 `TEXT NOT` bug that crashed
    // every DB≤5 upgrade at open. Test-scope only; never ships.
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.0")

    // Instrumented testing
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
