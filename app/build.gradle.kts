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
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "unwrapMedia"
            packageVersion = "1.6.0"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            macOS {
                iconFile.set(project.layout.projectDirectory.file("icons/app.icns"))
            }

            windows {
                // No jpackage-produced installer for Windows anymore (see targetFormats above) --
                // an Inno Setup script wraps the createDistributable app-image instead and owns
                // shortcuts/Start Menu/desktop icon. This icon still gets baked into the .exe
                // itself by jpackage regardless of which installer wraps it.
                iconFile.set(project.layout.projectDirectory.file("icons/app.ico"))
            }

            linux {
                iconFile.set(project.layout.projectDirectory.file("icons/app_source.png"))
                shortcut = true
            }
        }
    }
}
