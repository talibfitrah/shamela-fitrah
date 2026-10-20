import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "org.murabbie.shamela"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.murabbie.shamela"
        minSdk = 24
        targetSdk = 35
        versionCode = 20
        versionName = "1.5.0"
        vectorDrawables.useSupportLibrary = true
        // رابط manifest الدائم على NAS (احتياطي إن تعذّرت الواجهة الموثَّقة)
        buildConfigField("String", "DEFAULT_MANIFEST_URL", "\"${project.findProperty("manifestUrl") ?: "https://files.murabbie.org/fsdownload/FnGtHqpG9/manifest.json"}\"")
        buildConfigField("String", "SHAMELA_SITE", "\"https://shamela.ws\"")
    }

    // نكهتان: nas (توزيع مباشر بملف APK من الخادم مع تحديث ذاتي) و play (متجر Google Play: بلا إذن تثبيت الحزم وبلا زر التحديث الذاتي)
    flavorDimensions += "dist"
    productFlavors {
        create("nas") { dimension = "dist"; buildConfigField("Boolean", "PLAY_STORE", "false") }
        create("play") { dimension = "dist"; buildConfigField("Boolean", "PLAY_STORE", "true") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug { isMinifyEnabled = false }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/LICENSE.md", "META-INF/NOTICE.md")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
    implementation("androidx.sqlite:sqlite:2.5.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")   // استخراج نص PDF لإدراج الكتب
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
