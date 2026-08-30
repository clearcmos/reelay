import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

// Release signing: a gitignored keystore.properties at the repo root, written by hand for
// local release builds and by the release workflow from repository secrets. Absent file
// means an unsigned release build, which the workflow rejects.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
    }

android {
    namespace = "com.clearcmos.reelay"
    compileSdk = 36
    // Pinned so the read-only nix SDK is never asked to download a different revision.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.clearcmos.reelay"
        minSdk = 29
        targetSdk = 36
        // Overridable from the release workflow: -PversionName=<tag> -PversionCode=<run number>.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 stays off: Media3 Transformer loads codecs reflectively and the app is
            // 17 MB either way. Turn it on deliberately, with a device test.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // android.jar stubs return defaults instead of throwing, so Media3's common
        // classes (which touch android.os.Build in static initialisers) load in JVM tests.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Version-drift checks would fail CI whenever upstream ships something newer;
        // dependency and SDK bumps are deliberate, reviewed changes here (dependabot opens them).
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
}

kover {
    reports {
        filters {
            excludes {
                // Android-bound classes verified on a device, not in JVM tests. Each one is
                // listed with its reason in CLAUDE.md under "Test exemptions".
                classes(
                    "com.clearcmos.reelay.MainActivity*",
                    "com.clearcmos.reelay.ShareActivity*",
                    "com.clearcmos.reelay.RelayException",
                    "com.clearcmos.reelay.InstagramWebFetcher*",
                    "com.clearcmos.reelay.VideoNormalizer*",
                    "com.clearcmos.reelay.TikTokHandoff*",
                    "com.clearcmos.reelay.CleanupJobService*"
                )
            }
        }
        variant("debug") {
            verify {
                rule("line coverage of the JVM-testable modules") {
                    minBound(85)
                }
            }
        }
    }
}

// Every resolvable configuration is locked; gradle.lockfile is the ecosystem lockfile.
// Refresh deliberately with: gradle dependencies :app:dependencies --write-locks
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    testImplementation(libs.junit)
}
