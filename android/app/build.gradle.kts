plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.codexops.client"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.codexops.client"
        minSdk = 29
        targetSdk = 36
        versionCode = 38
        versionName = "0.4.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (providers.environmentVariable("REMOTE_CODEX_KEYSTORE").isPresent)
            create("personal") {
                storeFile = file(providers.environmentVariable("REMOTE_CODEX_KEYSTORE").get())
                storePassword = providers.environmentVariable("REMOTE_CODEX_STORE_PASSWORD").get()
                keyAlias = "remote-codex"
                keyPassword = storePassword
            }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("personal") != null)
                signingConfig = signingConfigs.getByName("personal")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    val releaseCertificate = rootProject.file("../docs/signing-certificate.sha256").readText().trim()
    defaultConfig {
        buildConfigField("String", "RELEASE_CERTIFICATE_SHA256", "\"$releaseCertificate\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        managedDevices {
            localDevices {
                create("remoteCodexApi36") {
                    device = "Pixel 7"
                    apiLevel = 36
                    systemImageSource = "google"
                    testedAbi = "x86_64"
                }
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.37.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestUtil("androidx.test:orchestrator:1.6.1")
}
