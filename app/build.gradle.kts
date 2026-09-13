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
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
    implementation(libs.pty4j)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// jediterm-core's POM declares a spurious kotlin-stdlib:2.4.0 compile dep that the
// pure-Java lib never uses; pin it back to the toolchain version so the runtime
// classpath stdlib stays consistent with Kotlin 2.0.21 and the version-skew warning
// is silenced. (The compile failure itself is handled by the flag below.)
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    }
}

// jediterm-core / jediterm-ui 3.74 ship a stray META-INF/*.kotlin_module stamped with
// Kotlin metadata version 2.4.0 (the jars are pure Java otherwise: 0 Kotlin classes,
// 0 kotlin/ bytecode refs). The 2.0.21 compiler rejects the newer metadata on the
// classpath scan, so skip the check. Safe today; REMOVE this when the Kotlin toolchain
// is upgraded, or it could mask a real metadata incompatibility in a future dependency.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
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
            packageVersion = "1.13.0"
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
