import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import groovy.json.JsonSlurper

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "app.perroute"
version = "3.6.0"

val sourceRevision = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }
val sourceBuildNumber = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.map { it.trim() }
val generatedBuildInfo = layout.buildDirectory.dir("generated/build-info")
val generateBuildInfo = tasks.register("generateBuildInfo") {
    inputs.property("revision", sourceRevision)
    inputs.property("number", sourceBuildNumber)
    inputs.property("version", project.version.toString())
    outputs.dir(generatedBuildInfo)
    doLast {
        val directory = generatedBuildInfo.get().asFile.apply { mkdirs() }
        directory.resolve("perroute-build.properties").writeText(
            "version=${project.version}\nnumber=${sourceBuildNumber.get()}\nrevision=${sourceRevision.get()}\n")
    }
}
sourceSets.main { resources.srcDir(generatedBuildInfo) }
tasks.named("processResources") { dependsOn(generateBuildInfo) }

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
            packageVersion = "3.6.0"
            description = "Modern per-application proxy client"
            vendor = "PerRoute"
            macOS {
                bundleID = "app.perroute.spike"
                minimumSystemVersion = "13.0"
                iconFile.set(project.file("src/main/resources/icons/perroute.icns"))
            }
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

tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        if (System.getProperty("os.name") == "Mac OS X") {
            // Compose 1.8.2 copies app resources without preserving Unix execute bits.
            // Restore Darwin engine permissions in the local image before it is packaged for delivery.
            val resources = layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app/Contents/app/resources/xray").get().asFile
            for (folder in listOf("macos-arm64", "macos-x64")) {
                val engine = resources.resolve("$folder/xray")
                if (engine.isFile) check(engine.setExecutable(true, false)) { "Cannot make packaged Xray executable: $engine" }
            }
        }
    }
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
    systemProperty("perroute.portOffset", "20000")
}

// Runs platform selection, diagnostics and fake-backed maintenance/local/system regressions.
tasks.register<JavaExec>("runPlatformBoundaryTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.PlatformBoundaryTests")
    enableAssertions = true
    systemProperty("perroute.portOffset", "20000")
}

// Keep the v3 suites reachable even when a legacy Windows-path test stops runAllTests on another host.
listOf("Domain" to "domain", "Controller" to "controller", "5" to "v35", "Features" to "features").forEach { (name, suite) ->
    tasks.register<JavaExec>("runV3${name}Tests") {
        dependsOn(tasks.named("testClasses"))
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("app.perroute.V3RegressionTests")
        args(suite)
        enableAssertions = true
        systemProperty("perroute.portOffset", "20000")
    }
}

// Isolated native feasibility targets. This does not package, sign, install or activate an extension.
val macosSpikeOutput = layout.buildDirectory.dir("macos-spike")
val buildMacosExtensionSpike = tasks.register<Exec>("buildMacosExtensionSpike") {
    inputs.dir(layout.projectDirectory.dir("native/macos-spike"))
    outputs.dir(macosSpikeOutput)
    doFirst {
        check(System.getProperty("os.name").lowercase().let { it == "mac os x" || it == "darwin" }) {
            "The native extension spike requires macOS with Xcode"
        }
    }
    commandLine("xcodebuild", "-project", "native/macos-spike/PerRouteMacosSpike.xcodeproj",
        "-scheme", "PerRouteMacosSpike", "-configuration", "Debug",
        "-derivedDataPath", macosSpikeOutput.get().asFile.absolutePath,
        "CODE_SIGNING_ALLOWED=NO", "build")
}

tasks.register<JavaExec>("inspectMacosExtensionSpike") {
    dependsOn(tasks.named("classes"), buildMacosExtensionSpike)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.spike.MacExtensionSpike")
    args(macosSpikeOutput.get().file("Build/Products/Debug/libPerRouteExtensionBridge.dylib").asFile.absolutePath)
}

tasks.register<JavaExec>("runMacosExtensionSpikeTests") {
    dependsOn(tasks.named("testClasses"), buildMacosExtensionSpike)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosExtensionSpikeTests")
    args(macosSpikeOutput.get().file("Build/Products/Debug/libPerRouteExtensionBridge.dylib").asFile.absolutePath)
    enableAssertions = true
}

// Copy the real Compose app image; never modify the base image or install into Applications here.
val macosSpikeApp = layout.buildDirectory.dir("macos-spike-app/PerRoute.app")
val stageMacosExtensionSpikeApp = tasks.register<Sync>("stageMacosExtensionSpikeApp") {
    dependsOn(tasks.named("createDistributable"), buildMacosExtensionSpike)
    into(macosSpikeApp)
    from(layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app"))
    from(macosSpikeOutput.map { it.dir("Build/Products/Debug") }) {
        include("libPerRouteExtensionBridge.dylib")
        into("Contents/Frameworks")
    }
    from(macosSpikeOutput.map { it.dir("Build/Products/Debug/app.perroute.spike.transparent-proxy.systemextension") }) {
        into("Contents/Library/SystemExtensions/app.perroute.spike.transparent-proxy.systemextension")
    }
    doLast {
        check(macosSpikeApp.get().file("Contents/MacOS/PerRoute").asFile.isFile) {
            "Compose application launcher is missing"
        }
        check(macosSpikeApp.get().file("Contents/Library/SystemExtensions/app.perroute.spike.transparent-proxy.systemextension/Contents/MacOS/PerRouteSpikeExtension").asFile.isFile) {
            "Embedded system extension executable is missing"
        }
    }
}

tasks.register<Exec>("inspectMacosExtensionSpikeApp") {
    dependsOn(stageMacosExtensionSpikeApp)
    val output = ByteArrayOutputStream()
    standardOutput = output
    commandLine(macosSpikeApp.get().file("Contents/MacOS/PerRoute").asFile.absolutePath,
        "--extension-spike", macosSpikeApp.get().file("Contents/Frameworks/libPerRouteExtensionBridge.dylib").asFile.absolutePath)
    doLast {
        val snapshot = JsonSlurper().parseText(output.toString(Charsets.UTF_8)) as Map<*, *>
        check(snapshot["bundleIdentifier"] == "app.perroute.spike") { "Probe did not identify the Compose container: $snapshot" }
        check(file(snapshot["bundlePath"].toString()).canonicalFile == macosSpikeApp.get().asFile.canonicalFile) {
            "Probe ran in a different app container: $snapshot"
        }
        check(snapshot["state"] == "idle" && snapshot["requestPending"] == false) { "Inspection must not submit an OS request" }
        val evidence = layout.buildDirectory.file("macos-spike-app/inspection.json").get().asFile
        evidence.writeText(output.toString(Charsets.UTF_8))
        println("Compose container bridge inspection passed: ${snapshot["bundleIdentifier"]}")
    }
}

tasks.register<JavaExec>("runXrayRuntimeTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.XrayRuntimeTests")
    enableAssertions = true
}

// Explicit local-only traffic test with a developer-supplied official Darwin engine.
tasks.register<JavaExec>("runMacosEngineSmoke") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosEngineSmokeTests")
    systemProperty("perroute.portOffset", "20000")
    args(layout.projectDirectory.dir("tools/xray").asFile.absolutePath)
    enableAssertions = true
}

tasks.register<JavaExec>("runMacosDesktopTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosDesktopTests")
    args(layout.projectDirectory.dir("tools/xray").asFile.absolutePath)
    systemProperty("perroute.portOffset", "20000")
    enableAssertions = true
}

tasks.register<JavaExec>("runMacosSecureStateTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.MacSecureStateTests")
    enableAssertions = true
}

tasks.register<JavaExec>("runMacosNativeKeychainTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.MacNativeKeychainTests")
    enableAssertions = true
}

tasks.register<JavaExec>("runXrayFailureTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.service.XrayFailureTests")
    args(layout.projectDirectory.dir("tools/xray").asFile.absolutePath)
    enableAssertions = true
}

tasks.register<JavaExec>("runMacosFailureTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosFailureTests")
    args(layout.projectDirectory.dir("tools/xray").asFile.absolutePath)
    systemProperty("perroute.portOffset", "20000")
    enableAssertions = true
}

// Policy and encrypted journal only: all network-service settings are simulated.
tasks.register<JavaExec>("runMacosProxyOwnershipTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.SystemProxyOwnershipTests")
    enableAssertions = true
}

tasks.register<JavaExec>("runMacosNetworkServiceTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.NetworkServiceInventoryTests")
    enableAssertions = true
}

// Read-only host inspection plus in-memory CoreFoundation fixtures; no native settings writer is bound.
tasks.register<JavaExec>("runMacosNativeNetworkServiceTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.NativeNetworkServiceTests")
    enableAssertions = true
}

// Pure projection fixtures. No native settings writer or traffic is involved.
tasks.register<JavaExec>("runMacosProxyProjectionTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.EffectiveProxyProjectionTests")
    enableAssertions = true
}

// Pure read-back/race and normalized ownership fixtures.
tasks.register<JavaExec>("runMacosProxyReadbackTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.ProxyReadbackTests")
    enableAssertions = true
}

// Injected preferences failures and native CF encoding only; never writes host preferences.
tasks.register<JavaExec>("runMacosNativeProxyWriterTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.NativeProxyWriterTests")
    enableAssertions = true
}

// Lifecycle policy uses fake settings; controller fixtures use owned loopback engines only.
tasks.register<JavaExec>("runMacosSystemProxyServiceTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.SystemProxyServiceTests")
    enableAssertions = true
}
tasks.register<JavaExec>("runMacosSystemProxyLifecycleTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosSystemProxyLifecycleTests")
    args(layout.projectDirectory.dir("tools/xray").asFile.absolutePath)
    enableAssertions = true
}
// Read-only observer for a separately authorized GUI Connect/Disconnect acceptance session.
tasks.register<JavaExec>("runMacosProxyAcceptance") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.NativeProxyAcceptance")
    args(providers.gradleProperty("proxySnapshotMode").getOrElse("verify"),
        providers.gradleProperty("proxySnapshotFile").getOrElse("/tmp/perroute-proxy-acceptance.json"))
    enableAssertions = true
}

// Local builds compile both Darwin architectures without registering a service or requesting privileges.
val macosHelperOutput = layout.buildDirectory.dir("macos-helper")
val helperSources = layout.projectDirectory.dir("native/macos-helper")
val buildMacosProxyHelper = tasks.register<Exec>("buildMacosProxyHelper") {
    inputs.dir(helperSources)
    inputs.files("native/macos-tun/TunStore.h", "native/macos-tun/TunStore.m", "native/macos-tun/TunSession.h", "native/macos-tun/TunSession.m", "native/macos-tun/EngineWorker.h", "native/macos-tun/EngineWorker.c",
        "native/macos-tun/TunCapture.h", "native/macos-tun/CoreOwnership.h", "native/macos-tun/CoreOwnership.m",
        "native/macos-tun/TunAuthority.h", "native/macos-tun/TunAuthority.m", "native/macos-tun/TunNetwork.h", "native/macos-tun/TunNetwork.m",
        "native/macos-tun/TunPolicy.h", "native/macos-tun/TunPolicy.m")
    outputs.file(macosHelperOutput.map { it.file("PerRouteProxyHelper") })
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { macosHelperOutput.get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-fobjc-arc", "-fmodules", "-Wall", "-Wextra", "-Werror",
        "-mmacosx-version-min=13.0", "-arch", "arm64", "-arch", "x86_64",
        "-framework", "Foundation", "-framework", "Security", "-framework", "SystemConfiguration",
        "native/macos-helper/ProxyPolicy.m", "native/macos-helper/ProxyJournal.m", "native/macos-helper/ProxyHelper.m",
        "native/macos-tun/TunStore.m", "native/macos-tun/TunSession.m", "native/macos-tun/EngineWorker.c",
        "native/macos-helper/TunService.m", "native/macos-tun/CoreOwnership.m", "native/macos-tun/TunAuthority.m",
        "native/macos-tun/TunNetwork.m", "native/macos-tun/TunPolicy.m",
        "-Wl,-sectcreate,__TEXT,__info_plist,${helperSources.file("HelperInfo.plist").asFile.absolutePath}",
        "-o", macosHelperOutput.get().file("PerRouteProxyHelper").asFile.absolutePath)
}
val buildMacosProxyBridge = tasks.register<Exec>("buildMacosProxyBridge") {
    inputs.dir(helperSources)
    outputs.file(macosHelperOutput.map { it.file("libPerRouteProxyBridge.dylib") })
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { macosHelperOutput.get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-dynamiclib", "-fobjc-arc", "-fmodules", "-Wall", "-Wextra", "-Werror",
        "-mmacosx-version-min=13.0", "-arch", "arm64", "-arch", "x86_64",
        "-framework", "Foundation", "-framework", "Security", "-framework", "ServiceManagement",
        "native/macos-helper/ProxyPolicy.m", "native/macos-helper/ProxyBridge.m",
        "-install_name", "@rpath/libPerRouteProxyBridge.dylib",
        "-o", macosHelperOutput.get().file("libPerRouteProxyBridge.dylib").asFile.absolutePath)
}
val compileMacosProxyHelperTests = tasks.register<Exec>("compileMacosProxyHelperTests") {
    inputs.dir(helperSources)
    outputs.file(macosHelperOutput.map { it.file("PolicyTests") })
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { macosHelperOutput.get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-fobjc-arc", "-fmodules", "-Wall", "-Wextra", "-Werror",
        "-mmacosx-version-min=13.0", "-framework", "Foundation", "-framework", "Security",
        "native/macos-helper/ProxyPolicy.m", "native/macos-helper/ProxyJournal.m", "native/macos-helper/PolicyTests.m",
        "-o", macosHelperOutput.get().file("PolicyTests").asFile.absolutePath)
}
tasks.register<Exec>("runMacosProxyHelperPolicyTests") {
    dependsOn(compileMacosProxyHelperTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(macosHelperOutput.get().file("PolicyTests").asFile.absolutePath)
}
tasks.matching { it.name == "createDistributable" }.configureEach {
    if (System.getProperty("os.name") == "Mac OS X") {
        dependsOn(buildMacosProxyHelper, buildMacosProxyBridge)
        inputs.dir(helperSources)
        inputs.files(buildMacosProxyHelper, buildMacosProxyBridge)
        doLast {
            val contents = layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app/Contents").get().asFile
            copy { from(macosHelperOutput.map { it.file("PerRouteProxyHelper") }); into(contents.resolve("Library/HelperTools")) }
            copy { from(helperSources.file("app.perroute.proxy-helper.plist")); into(contents.resolve("Library/LaunchDaemons")) }
            copy { from(macosHelperOutput.map { it.file("libPerRouteProxyBridge.dylib") }); into(contents.resolve("Frameworks")) }
            check(contents.resolve("Library/HelperTools/PerRouteProxyHelper").setExecutable(true, false))
        }
    }
}
tasks.register<JavaExec>("runMacosProxyHelperTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.HelperLifecycleTests")
    enableAssertions = true
}

tasks.register<JavaExec>("runMacosProxyHelperBridgeTests") {
    dependsOn(tasks.named("testClasses"), buildMacosProxyBridge)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.HelperBridgeTests")
    args(macosHelperOutput.get().file("libPerRouteProxyBridge.dylib").asFile.absolutePath)
    enableAssertions = true
}

// Status-only native feasibility checks do not create identities or register a daemon.
tasks.register<Exec>("runMacosSelfSignedHelperProbeTests") {
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine("python3", "scripts/macos_helper_probe.py", "test")
}

// Simulated maintenance never registers/unregisters a service or changes host networking.
tasks.register<JavaExec>("runMacosHelperMaintenanceTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.proxy.HelperMaintenanceTests")
    enableAssertions = true
}

val buildMacosCoreSupervisor = tasks.register<Exec>("buildMacosCoreSupervisor") {
    inputs.file("native/macos-core/CoreSupervisor.c")
    outputs.file(layout.buildDirectory.file("macos-core/PerRouteCoreSupervisor"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { layout.buildDirectory.dir("macos-core").get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-Wall", "-Wextra", "-Werror", "-arch", "arm64", "-arch", "x86_64",
        "-mmacosx-version-min=13.0", "native/macos-core/CoreSupervisor.c", "-o",
        layout.buildDirectory.file("macos-core/PerRouteCoreSupervisor").get().asFile.absolutePath)
}
val buildMacosDesktopBridge = tasks.register<Exec>("buildMacosDesktopBridge") {
    inputs.dir("native/macos-desktop")
    outputs.file(layout.buildDirectory.file("macos-desktop/libPerRouteDesktopBridge.dylib"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { layout.buildDirectory.dir("macos-desktop").get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-fobjc-arc", "-fmodules", "-Wall", "-Wextra", "-Werror", "-dynamiclib",
        "-arch", "arm64", "-arch", "x86_64", "-mmacosx-version-min=13.0", "-framework", "AppKit", "-framework", "Security",
        "native/macos-desktop/ApplicationCatalog.m", "native/macos-desktop/ApplicationIcons.m", "-o", layout.buildDirectory.file("macos-desktop/libPerRouteDesktopBridge.dylib").get().asFile.absolutePath)
}
tasks.matching { it.name == "createDistributable" }.configureEach {
    if (System.getProperty("os.name") == "Mac OS X") {
        dependsOn(buildMacosDesktopBridge, buildMacosCoreSupervisor)
        inputs.files(buildMacosDesktopBridge, buildMacosCoreSupervisor)
        doLast {
            copy {
                from(layout.buildDirectory.file("macos-core/PerRouteCoreSupervisor"))
                into(layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app/Contents/MacOS"))
            }
            copy {
                from(layout.buildDirectory.file("macos-desktop/libPerRouteDesktopBridge.dylib"))
                into(layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app/Contents/Frameworks"))
            }
        }
    }
}
val buildMacosApplicationFamilyTests = tasks.register<Exec>("buildMacosApplicationFamilyTests") {
    inputs.dir("native/macos-desktop")
    outputs.file(layout.buildDirectory.file("macos-desktop/ApplicationFamilyTests"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { layout.buildDirectory.dir("macos-desktop").get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-fobjc-arc", "-fmodules", "-Wall", "-Wextra", "-Werror",
        "-mmacosx-version-min=13.0", "-framework", "AppKit", "-framework", "Security",
        "native/macos-desktop/ApplicationFamilyTests.m", "-o",
        layout.buildDirectory.file("macos-desktop/ApplicationFamilyTests").get().asFile.absolutePath)
}
tasks.register<Exec>("runMacosApplicationFamilyTests") {
    dependsOn(buildMacosApplicationFamilyTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-desktop/ApplicationFamilyTests").get().asFile.absolutePath)
}

tasks.register<JavaExec>("runMacosApplicationCatalogTests") {
    dependsOn(tasks.named("testClasses"), buildMacosDesktopBridge)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosApplicationCatalogTests")
    enableAssertions = true
}

tasks.register<Exec>("runMacosPreviewPackagingTests") {
    commandLine("python3", "scripts/test_macos_preview.py")
}
tasks.register<Exec>("packageMacosPreview") {
    dependsOn(tasks.named("createDistributable"), tasks.named("runMacosPreviewPackagingTests"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine("python3", "scripts/macos_preview.py", "--app",
        layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app").get().asFile.absolutePath,
        "--output", layout.buildDirectory.dir("macos-preview").get().asFile.absolutePath)
}

// Explicit local signing uses the persistent developer identity; this task never installs a daemon.
tasks.register<Exec>("stageMacosLocalHelper") {
    dependsOn(tasks.named("createDistributable"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine("python3", "scripts/macos_local_helper.py", "--app",
        layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app").get().asFile.absolutePath)
}

tasks.register<JavaExec>("runMacosPerAppRoutingPlanTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosPerAppRoutingPlanTests")
    args(layout.buildDirectory.file("macos-per-app-routing/candidate.json").get().asFile.absolutePath)
    enableAssertions = true
}

tasks.register<Exec>("checkMacosPerAppRoutingCandidate") {
    dependsOn(tasks.named("runMacosPerAppRoutingPlanTests"))
    commandLine("python3", "scripts/verify_macos_per_app.py", "--config",
        layout.buildDirectory.file("macos-per-app-routing/candidate.json").get().asFile.absolutePath)
}

val buildMacosProcessRoutingClient = tasks.register<Exec>("buildMacosProcessRoutingClient") {
    inputs.file("native/macos-routing-proof/Client.c")
    outputs.file(layout.buildDirectory.file("macos-per-app-routing/Client"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { layout.buildDirectory.dir("macos-per-app-routing").get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-Wall", "-Wextra", "-Werror", "-mmacosx-version-min=13.0",
        "native/macos-routing-proof/Client.c", "-o",
        layout.buildDirectory.file("macos-per-app-routing/Client").get().asFile.absolutePath)
}

val buildMacosOwnedSocketProbe = tasks.register<Exec>("buildMacosOwnedSocketProbe") {
    inputs.file("native/macos-routing-proof/OwnerProbe.c")
    outputs.file(layout.buildDirectory.file("macos-per-app-routing/OwnerProbe"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { layout.buildDirectory.dir("macos-per-app-routing").get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-Wall", "-Wextra", "-Werror", "-mmacosx-version-min=13.0",
        "native/macos-routing-proof/OwnerProbe.c", "-o",
        layout.buildDirectory.file("macos-per-app-routing/OwnerProbe").get().asFile.absolutePath)
}

tasks.register<Exec>("proveMacosProcessRouting") {
    dependsOn(tasks.named("testClasses"), buildMacosProcessRoutingClient, buildMacosOwnedSocketProbe)
    // Deliberately opt-in and separate from normal builds and the installed application's ports.
    doFirst {
        check(System.getProperty("os.name") == "Mac OS X") { "Native process routing proof requires macOS" }
        commandLine("python3", "scripts/prove_macos_process_routing.py", "--java",
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().executablePath.asFile.absolutePath,
            "--classpath", sourceSets["test"].runtimeClasspath.asPath, "--client",
            layout.buildDirectory.file("macos-per-app-routing/Client").get().asFile.absolutePath,
            "--probe", layout.buildDirectory.file("macos-per-app-routing/OwnerProbe").get().asFile.absolutePath)
        if (providers.gradleProperty("privilegedRoutingProof").map { it.toBoolean() }.getOrElse(false)) args("--privileged")
        if (providers.gradleProperty("preparePrivilegedRoutingProof").map { it.toBoolean() }.getOrElse(false)) args("--prepare-privileged")
        if (providers.gradleProperty("nativeAttributionOnly").map { it.toBoolean() }.getOrElse(false)) args("--native-only")
    }
}

tasks.register<Exec>("runMacosProcessProofGuardTests") {
    commandLine("python3", "scripts/test_macos_process_proof.py")
}

val tunPolicyFixture = layout.buildDirectory.file("macos-tun/policy-fixture.json")
val runMacosTunPreparationTests = tasks.register<JavaExec>("runMacosTunPreparationTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosTunPreparationTests")
    args(tunPolicyFixture.get().asFile.absolutePath)
    enableAssertions = true
}
val compileMacosTunPolicyTests = tasks.register<Exec>("compileMacosTunPolicyTests") {
    inputs.files("native/macos-tun/TunPolicy.h", "native/macos-tun/TunCapture.h", "native/macos-tun/TunPolicy.m", "native/macos-tun/PolicyTests.m")
    outputs.file(layout.buildDirectory.file("macos-tun/policy-tests"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { layout.buildDirectory.dir("macos-tun").get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-Wall", "-Wextra", "-Werror", "-fobjc-arc", "-mmacosx-version-min=13.0",
        "-framework", "Foundation", "native/macos-tun/TunPolicy.m", "native/macos-tun/PolicyTests.m", "-o",
        layout.buildDirectory.file("macos-tun/policy-tests").get().asFile.absolutePath)
}
tasks.register<Exec>("runMacosTunPolicyTests") {
    dependsOn(runMacosTunPreparationTests, compileMacosTunPolicyTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-tun/policy-tests").get().asFile.absolutePath,
        tunPolicyFixture.get().asFile.absolutePath,
        layout.buildDirectory.file("macos-tun/native-candidate.json").get().asFile.absolutePath)
}

tasks.register<Exec>("checkMacosTunPolicyCandidate") {
    dependsOn(tasks.named("runMacosTunPolicyTests"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine("python3", "scripts/verify_macos_per_app.py", "--config",
        layout.buildDirectory.file("macos-tun/native-candidate.json").get().asFile.absolutePath)
}

val compileMacosTunWorkerTests = tasks.register<Exec>("compileMacosTunWorkerTests") {
    inputs.files("native/macos-tun/EngineWorker.h", "native/macos-tun/EngineWorker.c", "native/macos-tun/WorkerTests.c")
    outputs.file(layout.buildDirectory.file("macos-tun/worker-tests"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { layout.buildDirectory.dir("macos-tun").get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-Wall", "-Wextra", "-Werror", "-mmacosx-version-min=13.0",
        "native/macos-tun/EngineWorker.c", "native/macos-tun/WorkerTests.c", "-o",
        layout.buildDirectory.file("macos-tun/worker-tests").get().asFile.absolutePath)
}
val compileMacosTunWorkerFixture = tasks.register<Exec>("compileMacosTunWorkerFixture") {
    inputs.file("native/macos-tun/WorkerFixture.c")
    outputs.file(layout.buildDirectory.file("macos-tun/worker-fixture"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { layout.buildDirectory.dir("macos-tun").get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-Wall", "-Wextra", "-Werror", "-mmacosx-version-min=13.0",
        "native/macos-tun/WorkerFixture.c", "-o",
        layout.buildDirectory.file("macos-tun/worker-fixture").get().asFile.absolutePath)
}
tasks.register<Exec>("runMacosTunWorkerTests") {
    dependsOn(compileMacosTunWorkerTests, compileMacosTunWorkerFixture)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-tun/worker-tests").get().asFile.absolutePath,
        layout.buildDirectory.file("macos-tun/worker-fixture").get().asFile.absolutePath)
}

// Same-user recovery tests use disposable ad-hoc fixtures, never the installed helper or TUN capture.
fun tunTestBinary(taskName: String, binary: String, sources: List<String>, foundation: Boolean = true) = tasks.register<Exec>(taskName) {
    inputs.files(sources)
    inputs.files("native/macos-tun/TunStore.h", "native/macos-tun/TunSession.h", "native/macos-tun/EngineWorker.h",
        "native/macos-tun/TunCapture.h", "native/macos-tun/CoreOwnership.h", "native/macos-tun/TunAuthority.h", "native/macos-tun/TunNetwork.h")
    outputs.file(layout.buildDirectory.file("macos-tun/$binary"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { layout.buildDirectory.dir("macos-tun").get().asFile.mkdirs() }
    commandLine(listOf("xcrun", "clang", "-Wall", "-Wextra", "-Werror", "-mmacosx-version-min=13.0") +
        (if (foundation) listOf("-fobjc-arc", "-framework", "Foundation", "-framework", "Security") else emptyList()) +
        sources + listOf("-o", layout.buildDirectory.file("macos-tun/$binary").get().asFile.absolutePath))
}
fun tunFixtureSignature(taskName: String, binary: String, identity: String, dependency: org.gradle.api.tasks.TaskProvider<Exec>) = tasks.register<Exec>(taskName) {
    dependsOn(dependency)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine("/usr/bin/codesign", "--force", "--sign", "-", "--identifier", identity,
        layout.buildDirectory.file("macos-tun/$binary").get().asFile.absolutePath)
}
val responsibleApplicationTests = tunTestBinary("compileMacosResponsibleApplicationTests", "responsible-application-tests",
    listOf("native/macos-tun/ResponsibleApplication.c", "native/macos-tun/ResponsibleApplicationTests.c"), false)
val responsibleApplicationProbe = tunTestBinary("compileMacosResponsibleApplicationProbe", "responsible-application-probe",
    listOf("native/macos-tun/ResponsibleApplication.c", "native/macos-tun/ResponsibleApplicationDarwin.c",
        "native/macos-tun/ResponsibleApplicationProbe.c"), false)
val responsibleConnectionDarwinTests = tunTestBinary("compileMacosResponsibleConnectionDarwinTests", "responsible-connection-darwin-tests",
    listOf("native/macos-tun/ResponsibleApplication.c", "native/macos-tun/ResponsibleApplicationDarwin.c",
        "native/macos-tun/ResponsibleConnectionDarwinTests.c"), false)
listOf(responsibleApplicationProbe, responsibleConnectionDarwinTests).forEach { task ->
    task.configure { args("-framework", "Security", "-framework", "CoreFoundation") }
}
listOf(responsibleApplicationTests, responsibleApplicationProbe, responsibleConnectionDarwinTests).forEach { task ->
    task.configure {
        inputs.files("native/macos-tun/ResponsibleApplication.h", "native/macos-tun/ResponsibleApplicationInternal.h")
    }
}
tasks.register<Exec>("runMacosResponsibleApplicationTests") {
    dependsOn(responsibleApplicationTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-tun/responsible-application-tests").get().asFile.absolutePath)
}
tasks.register<Exec>("runMacosResponsibleConnectionDarwinTests") {
    dependsOn(responsibleConnectionDarwinTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-tun/responsible-connection-darwin-tests").get().asFile.absolutePath)
}
val tunSessionSources = listOf("native/macos-tun/TunStore.m", "native/macos-tun/TunSession.m", "native/macos-tun/EngineWorker.c")
val storeFixture = tunTestBinary("compileMacosTunStoreFixture", "store-fixture", listOf("native/macos-tun/StoreFixture.c"), false)
val signStoreFixture = tunFixtureSignature("signMacosTunStoreFixture", "store-fixture", "app.perroute.store-fixture", storeFixture)
val storeTests = tunTestBinary("compileMacosTunStoreTests", "store-tests", listOf("native/macos-tun/TunStore.m", "native/macos-tun/StoreTests.m"))
tasks.register<Exec>("runMacosTunStoreTests") {
    dependsOn(signStoreFixture,storeTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-tun/store-tests").get().asFile.absolutePath,
        layout.buildDirectory.file("macos-tun/store-fixture").get().asFile.absolutePath)
}
val sessionEngine = tunTestBinary("compileMacosTunSessionEngine", "session-engine", listOf("native/macos-tun/WorkerFixture.c"), false)
val signSessionEngine = tunFixtureSignature("signMacosTunSessionEngine", "session-engine", "app.perroute.session-engine", sessionEngine)
val sessionWorker = tunTestBinary("compileMacosTunSessionWorker", "session-worker", tunSessionSources + "native/macos-tun/SessionFixture.m")
val signSessionWorker = tunFixtureSignature("signMacosTunSessionWorker", "session-worker", "app.perroute.session-worker", sessionWorker)
val sessionTests = tunTestBinary("compileMacosTunSessionTests", "session-tests", tunSessionSources + "native/macos-tun/SessionTests.m")
tasks.register<Exec>("runMacosTunSessionTests") {
    dependsOn(signSessionEngine,signSessionWorker,sessionTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-tun/session-tests").get().asFile.absolutePath,
        layout.buildDirectory.file("macos-tun/session-engine").get().asFile.absolutePath,
        layout.buildDirectory.file("macos-tun/session-worker").get().asFile.absolutePath)
}

val coreFixture = tunTestBinary("compileMacosTunCoreFixture", "core-fixture", listOf("native/macos-tun/CoreFixture.c"), false)
val signCoreFixture = tunFixtureSignature("signMacosTunCoreFixture", "core-fixture", "app.perroute.core-fixture", coreFixture)
val gatewayFixture = tunTestBinary("compileMacosTunGatewayFixture", "gateway-fixture", listOf("native/macos-tun/CoreFixture.c"), false)
val signGatewayFixture = tunFixtureSignature("signMacosTunGatewayFixture", "gateway-fixture", "app.perroute.gateway-fixture", gatewayFixture)
val coreTests = tunTestBinary("compileMacosTunCoreTests", "core-tests", listOf("native/macos-tun/TunStore.m", "native/macos-tun/CoreOwnership.m", "native/macos-tun/CoreTests.m"))
tasks.register<Exec>("runMacosTunCoreTests") {
    dependsOn(signCoreFixture,signGatewayFixture,coreTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-tun/core-tests").get().asFile.absolutePath,
        layout.buildDirectory.file("macos-tun/core-fixture").get().asFile.absolutePath,
        layout.buildDirectory.file("macos-tun/gateway-fixture").get().asFile.absolutePath)
}
val authorityTests = tunTestBinary("compileMacosTunAuthorityTests", "authority-tests", tunSessionSources + listOf("native/macos-tun/TunAuthority.m", "native/macos-tun/AuthorityTests.m"))
tasks.register<Exec>("runMacosTunAuthorityTests") {
    dependsOn(signSessionEngine,signSessionWorker,authorityTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-tun/authority-tests").get().asFile.absolutePath,
        layout.buildDirectory.file("macos-tun/session-engine").get().asFile.absolutePath,
        layout.buildDirectory.file("macos-tun/session-worker").get().asFile.absolutePath)
}
val networkTests = tunTestBinary("compileMacosTunNetworkTests", "network-tests",
    listOf("native/macos-tun/TunStore.m", "native/macos-tun/TunNetwork.m", "native/macos-tun/NetworkTests.m"))
networkTests.configure {
    args("-framework", "SystemConfiguration")
}
tasks.register<Exec>("runMacosTunNetworkTests") {
    dependsOn(networkTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-tun/network-tests").get().asFile.absolutePath)
}

tasks.register<JavaExec>("runMacosTunSessionClientTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.routing.MacTunSessionClientTests")
    enableAssertions = true
}

val capturePolicyTests = tunTestBinary("compileMacosTunCapturePolicyTests", "capture-policy-tests",
    listOf("native/macos-tun/TunPolicy.m", "native/macos-tun/CapturePolicyTests.m"))
tasks.register<Exec>("runMacosTunCapturePolicyTests") {
    dependsOn(capturePolicyTests)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(layout.buildDirectory.file("macos-tun/capture-policy-tests").get().asFile.absolutePath,
        layout.buildDirectory.file("macos-tun/native-active.json").get().asFile.absolutePath)
}

tasks.register<Exec>("checkMacosTunCaptureCandidate") {
    dependsOn(tasks.named("runMacosTunCapturePolicyTests"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "scripts/check_macos_tun_capture.py", "--config",
        layout.buildDirectory.file("macos-tun/native-active.json").get().asFile.absolutePath)
}

// Packaging only: no daemon registration, TUN activation or installed app replacement.
tasks.register<Exec>("runMacosRoutingBundleTests") {
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "scripts/test_macos_routing_bundle.py")
}
tasks.register<Exec>("runMacosLocalSigningTests") {
    group = "verification"
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "scripts/test_macos_local_signing.py")
}
tasks.register<Exec>("stageMacosRoutingHelper") {
    dependsOn(tasks.named("createDistributable"), tasks.named("runMacosRoutingBundleTests"), tasks.named("runMacosLocalSigningTests"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "scripts/macos_local_helper.py", "--app",
        layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app").get().asFile.absolutePath, "--routing")
}

tasks.register<Exec>("checkMacosRoutingBundle") {
    dependsOn(tasks.named("createDistributable"), tasks.named("runMacosRoutingBundleTests"), tasks.named("runMacosTunCapturePolicyTests"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "scripts/check_macos_routing_bundle.py", "--app",
        layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app").get().asFile.absolutePath,
        "--config", layout.buildDirectory.file("macos-tun/native-active.json").get().asFile.absolutePath)
}

tasks.register<JavaExec>("runApplicationIdentityTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.ApplicationIdentityTests")
    enableAssertions = true
}

// Actual loopback Xray/gateway exercise; the privileged helper is simulated and never activates capture.
tasks.register<JavaExec>("runMacosPerAppBackendTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.MacPerAppBackendTests")
    systemProperty("perroute.portOffset", "15000")
    args(layout.projectDirectory.dir("tools/xray").asFile.absolutePath)
    enableAssertions = true
}

val buildMacosCoreFixture = tasks.register<Exec>("buildMacosCoreFixture") {
    inputs.file("native/macos-core/CoreFixture.c")
    outputs.file(layout.buildDirectory.file("macos-core/core-fixture"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    doFirst { layout.buildDirectory.dir("macos-core").get().asFile.mkdirs() }
    commandLine("xcrun", "clang", "-Wall", "-Wextra", "-Werror", "native/macos-core/CoreFixture.c", "-o",
        layout.buildDirectory.file("macos-core/core-fixture").get().asFile.absolutePath)
}
tasks.register<Exec>("runMacosCoreSupervisorTests") {
    dependsOn(buildMacosCoreSupervisor, buildMacosCoreFixture)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine("python3", "scripts/test_macos_core_supervisor.py",
        layout.buildDirectory.file("macos-core/PerRouteCoreSupervisor").get().asFile.absolutePath,
        layout.buildDirectory.file("macos-core/core-fixture").get().asFile.absolutePath)
}
tasks.register<JavaExec>("runMacosCoreProcessTests") {
    dependsOn(tasks.named("testClasses"), buildMacosCoreSupervisor)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.service.MacCoreProcessTests")
    args(layout.buildDirectory.file("macos-core/PerRouteCoreSupervisor").get().asFile.absolutePath,
        layout.projectDirectory.dir("tools/xray").asFile.absolutePath)
    enableAssertions = true
}

// Repeat persistence, routes, rules, profiles, backups and history checks with explicit family identity.
tasks.register<JavaExec>("runMacosApplicationFamilyIdentityTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.ApplicationIdentityTests")
    args("family")
    enableAssertions = true
}

tasks.register<JavaExec>("runMacosApplicationFamilyRoutingTests") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.platform.macos.MacApplicationFamilyTests")
    enableAssertions = true
}

tasks.register<JavaExec>("runMacosApplicationIconTests") {
    dependsOn(tasks.named("testClasses"), buildMacosDesktopBridge)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.perroute.MacosApplicationIconTests")
    enableAssertions = true
}

tasks.register<Exec>("runMacosDmgTests") {
    group = "verification"
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "scripts/test_macos_dmg.py")
}

// Packages the already sealed helper-enabled app; never use Compose packageDmg, which
// would package the intermediate app before the matched native components are staged.
tasks.register<Exec>("packageMacosDmg") {
    group = "distribution"
    description = "Build and verify an account-free macOS preview DMG with install instructions and checksums"
    dependsOn(tasks.named("stageMacosRoutingHelper"), tasks.named("runMacosDmgTests"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "scripts/macos_dmg.py", "--app",
        layout.buildDirectory.dir("macos-routing-helper/PerRoute.app").get().asFile.absolutePath,
        "--output", layout.buildDirectory.dir("macos-installer").get().asFile.absolutePath)
}

tasks.register<Exec>("runMacosPkgTests") {
    group = "verification"
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "scripts/test_macos_pkg.py")
}

tasks.register<Exec>("packageMacosPkg") {
    group = "distribution"
    description = "Build and extract-verify a preview Installer package for Applications"
    dependsOn(tasks.named("stageMacosRoutingHelper"), tasks.named("runMacosDmgTests"), tasks.named("runMacosPkgTests"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "scripts/macos_pkg.py", "--app",
        layout.buildDirectory.dir("macos-routing-helper/PerRoute.app").get().asFile.absolutePath,
        "--output", layout.buildDirectory.dir("macos-pkg").get().asFile.absolutePath)
    providers.gradleProperty("previousMacosHelperRecovery").orNull?.let {
        args("--previous-helper-recovery", it)
    }
}

tasks.register<Exec>("packageMacosSafariPkg") {
    group = "distribution"
    description = "Build the source-identified Safari preview PKG without tests or installation"
    dependsOn(tasks.named("createDistributable"))
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"),
        "scripts/build_macos_safari_pkg.py", "--app",
        layout.buildDirectory.dir("compose/binaries/main/app/PerRoute.app").get().asFile.absolutePath)
    providers.gradleProperty("previousMacosHelperRecovery").orNull?.let {
        args("--previous-helper-recovery", it)
    }
}

val fetchMacosXray = tasks.register<Exec>("fetchMacosXray") {
    group = "distribution"
    description = "Download or verify the SHA-256-pinned host Xray for desktop packaging"
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "scripts/fetch_macos_xray.py")
}
stageNativeResources.configure {
    if (System.getProperty("os.name") == "Mac OS X") dependsOn(fetchMacosXray)
}
