import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yang136.sshhelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yang136.sshhelper"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    // Release signing reads credentials from ~/.android/ssh_helper-release.properties
    // (kept outside the repo). Without that file the release build stays unsigned.
    val releaseSigning = Properties().apply {
        val file = File(System.getProperty("user.home"), ".android/ssh_helper-release.properties")
        if (file.isFile) FileInputStream(file).use { load(it) }
    }
    signingConfigs {
        if (releaseSigning.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigning.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures.compose = true
    packaging.resources.excludes += setOf(
        "META-INF/AL2.0",
        "META-INF/LGPL2.1",
        "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    )
    testOptions.unitTests.isIncludeAndroidResources = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    // material3 1.4.0 提供 sheetGesturesEnabled（仅横杠可拖动关闭），显式覆盖 BOM 的 1.3.2。
    implementation("androidx.compose.material3:material3:1.4.0")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-process:2.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.palette:palette:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation("com.github.mwiede:jsch:2.28.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-datasource:1.9.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.apache.sshd:sshd-sftp:2.14.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
}
