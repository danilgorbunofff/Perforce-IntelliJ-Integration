import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.p4ii"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
        // VcsVFSListener (IDE renames/moves/deletes -> p4) lives here, as for every VCS plugin; tests also use
        // ChangeListManagerImpl's test-mode hooks from it
        bundledModule("intellij.platform.vcs.impl")
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // the oldest supported build and the newest releases: no until-build is claimed, so newer ones must stay clean
        ides {
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
            create(IntelliJPlatformType.IntellijIdea, "2026.1.5")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

// IDEA 2025.3 bundles JetBrains' Perforce plugin; plugin.xml declares it incompatible, so while it is enabled the
// platform disables THIS plugin. Test and run sandboxes start the way a user who switched has it: bundled one off.
tasks.prepareTestSandbox {
    disabledPlugins.add("PerforceDirectPlugin")
}
tasks.prepareSandbox {
    disabledPlugins.add("PerforceDirectPlugin")
}

tasks.test {
    // live tests run against a real p4d (rsh mode, no port) when P4_BIN points at a dir holding p4 and p4d
    providers.environmentVariable("P4_BIN").orNull?.let { environment("P4_BIN", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
