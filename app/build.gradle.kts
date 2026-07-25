import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("uk.co.caprica:vlcj:4.12.1")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.multiviewer.MainKt"
        jvmArgs += listOf("-Dapple.awt.application.name=unwrapMedia")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "unwrapMedia"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                // Without these, jpackage's MSI installs the app with no Start Menu entry and
                // no desktop icon -- it's on disk but unreachable from the UI.
                shortcut = true
                menuGroup = "unwrapMedia"
                menu = true
            }

            linux {
                shortcut = true
            }
        }
    }
}
