import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    // The Compose UI is shared with the Android app by source folder (see docs/ARCHITECTURE.md).
    sourceSets["main"].kotlin.srcDir("../shared-ui/src/main/kotlin")
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:${libs.versions.compose.multiplatform.get()}")
    implementation(libs.coroutines.swing)
    implementation(libs.qrcodegen)
}

compose.desktop {
    application {
        mainClass = "com.transferx.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "TransferX"
            packageVersion = "1.0.0"
            description = "Local, encrypted file transfer between phones and computers"
            vendor = "TransferX"
            // TLS with EC certificates, management beans and naming are needed at runtime.
            includeAllModules = true
            windows {
                menuGroup = "TransferX"
                upgradeUuid = "6c1b8d3e-7a52-4f0e-9a51-2f8f3b0d6e11"
            }
            macOS { bundleID = "app.transferx.desktop" }
            linux { packageName = "transferx" }
        }
    }
}
