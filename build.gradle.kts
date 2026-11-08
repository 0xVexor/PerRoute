import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "app.perroute"
version = "3.5.1"

val stageNativeResources = tasks.register<Sync>("stageNativeResources") {
    into(layout.buildDirectory.dir("native-resources"))
    from(layout.projectDirectory.dir("tools/xray")) { into("common/xray") }
    from(layout.projectDirectory.file("README.md")) { into("common") }
    from(layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) { into("common") }
    from(layout.projectDirectory.file("LICENSE")) { into("common") }
    from(zipTree(layout.projectDirectory.file("lib/jna-5.17.0.jar"))) {
        include("com/sun/jna/win32-x86-64/jnidispatch.dll")
        eachFile { path = "common/jnidispatch.dll" }
        includeEmptyDirs = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(files("lib/flatlaf-3.7.2.jar"))
    implementation(files("lib/jna-5.17.0.jar"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    implementation("com.github.sarxos:webcam-capture:0.3.12")
}

compose.desktop {
    application {
        mainClass = "app.perroute.compose.ComposeMainKt"
        jvmArgs += listOf("-XX:+UseSerialGC", "-Xms64m", "-Xmn32m", "-Xmx1g", "-XX:MinHeapFreeRatio=20", "-XX:MaxHeapFreeRatio=40")
        nativeDistributions {
            modules("java.net.http", "java.prefs", "java.naming", "jdk.naming.dns","jdk.crypto.ec", "jdk.unsupported")
            appResourcesRootDir.set(layout.buildDirectory.dir("native-resources"))
            targetFormats(TargetFormat.Exe)
            packageName = "PerRoute"
            packageVersion = "3.5.1"
            description = "Modern per-application proxy client"
            vendor = "PerRoute"
            windows {
                iconFile.set(project.file("src/main/resources/icons/perroute.ico"))
                console = false
                perUserInstall = true
                dirChooser = true
                menu = true
                shortcut = true
                menuGroup = "PerRoute"
                upgradeUuid = "3d39ae88-05df-4c36-ae6a-268c0ca6ed5a"
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(stageNativeResources)
}

tasks.matching { it.name in setOf("createDistributable", "packageExe") }.configureEach {
    dependsOn(stageNativeResources)
    inputs.dir(layout.buildDirectory.dir("native-resources"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests = false
}

// Renders every screen off-screen with demonstration data: baseline, narrow, 150% scaling, both themes.
// Pass scene names with -Pscenes=a,b to render only those.
tasks.register<JavaExec>("renderUiSnapshots") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.UiSnapshotsKt")
    args(layout.buildDirectory.dir("ui-snapshots").get().asFile.absolutePath)
    (project.findProperty("scenes") as String?)?.split(',')?.filter { it.isNotBlank() }?.let { args(it) }
}

tasks.register<JavaExec>("runAllTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.AllTests")
    enableAssertions = true
    // Shifts every PerRoute port so the real-core integration test can run next to an installed PerRoute.
    systemProperty("perroute.portOffset", "20000")
}
