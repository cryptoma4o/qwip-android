plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}
android {
    namespace = "com.qwipvpn.app"
    compileSdk = 36
    ndkVersion = "29.0.13599879"
    defaultConfig {
        applicationId = "com.qwipvpn.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("Support/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    // Production signing comes only from the owner's environment; no key material lives in the repository.
    val releaseKeystore = System.getenv("QWIP_RELEASE_KEYSTORE")
    val releaseSigning = if (releaseKeystore != null) signingConfigs.create("release") {
        storeFile = file(releaseKeystore)
        storePassword = System.getenv("QWIP_RELEASE_STORE_PASSWORD")
        keyAlias = System.getenv("QWIP_RELEASE_KEY_ALIAS")
        keyPassword = System.getenv("QWIP_RELEASE_KEY_PASSWORD")
    } else null
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            releaseSigning?.let { signingConfig = it }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { jniLibs.useLegacyPackaging = false }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    testOptions { animationsDisabled = true }
    sourceSets["androidTest"].assets.srcDir(layout.buildDirectory.dir("generated/fixtureAssets"))
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(files("libs/libXray.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("com.google.zxing:core:3.5.4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.register("runtimeInventory") {
    doLast {
        val output = rootProject.file(".artifacts/runtime-components.tsv")
        output.parentFile.mkdirs()
        output.writeText(configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group != rootProject.name }
            .map { "${it.moduleVersion.id.group}\t${it.name}\t${it.moduleVersion.id.version}\t${it.file.absolutePath}" }
            .sorted().joinToString("\n",postfix="\n"))
    }
}

val prepareDebugKey = tasks.register<Exec>("prepareDebugKey") {
    val key = rootProject.file("Support/debug.keystore")
    outputs.file(key)
    onlyIf { !key.exists() }
    doFirst { key.parentFile.mkdirs() }
    commandLine("${System.getProperty("java.home")}/bin/keytool", "-genkeypair", "-keystore", key.absolutePath,
        "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
        "-keysize", "2048", "-validity", "10000", "-dname", "CN=QWIP Development", "-noprompt")
}
tasks.matching { it.name.startsWith("validateSigningDebug") }.configureEach { dependsOn(prepareDebugKey) }

val verifyEngine = tasks.register<Exec>("verifyEngine") {
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/verify_engine.py")
}
tasks.named("preBuild").configure { dependsOn(verifyEngine) }
