import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

java {
    // Java 17 bytecode keeps the core consumable by Android (D8) and desktop alike.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    implementation(libs.bouncycastle.pkix)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // A deliberately small heap: the large-file test transfers more data than fits
    // in memory, so any accidental whole-file buffering fails with OutOfMemoryError.
    maxHeapSize = "256m"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "com.transferx"
            artifactId = "transferx-core"
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/twishamp2024/TransferX")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String? ?: "twishamp2024"
                password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.key") as String? ?: ""
            }
        }
    }
}
