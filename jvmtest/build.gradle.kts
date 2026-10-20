plugins {
    kotlin("jvm") version "2.0.21"
    application
}
repositories { google(); mavenCentral() }
dependencies {
    implementation("androidx.sqlite:sqlite-bundled-jvm:2.5.2")
    implementation("androidx.sqlite:sqlite-jvm:2.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../android/app/src/main/java/org/murabbie/shamela/data")
        kotlin.exclude("**/Services.kt", "**/NewBooks.kt", "**/Search.kt", "**/Settings.kt", "**/Library.kt", "**/Downloads.kt", "**/DownloadService.kt", "**/UserDb.kt", "**/Exporter.kt")
    }
}
application { mainClass.set("WebTestKt") }
