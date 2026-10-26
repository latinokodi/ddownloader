import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// Release signing is read from keystore.properties (git-ignored). When absent
// (e.g. a clean checkout / CI without secrets) the release build falls back to
// the debug keystore so `assembleRelease` still produces a runnable artifact.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// Version and artifact naming live in one place.
val appVersionCode = 21
val appVersionName = "1.3.2"

android {
    namespace = "com.ddownloader.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ddownloader.android"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // The embedded Comet (Stremio stream add-on) base URL. Kept identical to
        // the Electron app's CATALOG_BASE so both clients resolve the same add-on.
        buildConfigField(
            "String",
            "COMET_BASE",
            "\"https://comet.stremio.ru/s/WuPvYs_OtKH92vkg7iRetCW99cKdmIa0fBppZ0DftQk/eyJtYXhSZXN1bHRzUGVyUmVzb2x1dGlvbiI6MCwibWF4U2l6ZSI6MjE0NzQ4MzY0ODAsImNhY2hlZE9ubHkiOmZhbHNlLCJzb3J0Q2FjaGVkVW5jYWNoZWRUb2dldGhlciI6ZmFsc2UsInJlbW92ZVRyYXNoIjp0cnVlLCJyZXN1bHRGb3JtYXQiOlsiYWxsIl0sImRlYnJpZFNlcnZpY2VzIjpbXSwiZW5hYmxlVG9ycmVudCI6dHJ1ZSwiZGVkdXBsaWNhdGVTdHJlYW1zIjpmYWxzZSwic2NyYXBlRGVicmlkQWNjb3VudFRvcnJlbnRzIjpmYWxzZSwiZGVicmlkU3RyZWFtUHJveHlQYXNzd29yZCI6IiIsImxhbmd1YWdlcyI6eyJyZXF1aXJlZCI6WyJsYSJdLCJhbGxvd2VkIjpbXSwiZXhjbHVkZSI6W10sInByZWZlcnJlZCI6WyJsYSJdfSwicmVzb2x1dGlvbnMiOnsicjU3NnAiOmZhbHNlLCJyNDgwcCI6ZmFsc2UsInIzNjBwIjpmYWxzZSwicjI0MHAiOmZhbHNlfSwib3B0aW9ucyI6eyJyZW1vdmVfcmFua3NfdW5kZXIiOi0xMDAwMDAwMDAwMCwiYWxsb3dfZW5nbGlzaF9pbl9sYW5ndWFnZXMiOnRydWUsInJlbW92ZV91bmtub3duX2xhbmd1YWdlcyI6ZmFsc2V9fQ==\""
        )
        buildConfigField("boolean", "ALLOW_LOOPBACK_CLEARTEXT", "true")
        buildConfigField("boolean", "VERIFY_HARNESS", "false")
        // Self-update feed: this project's own GitHub Releases.
        buildConfigField("String", "UPDATE_REPO", "\"latinokodi/ddownloader\"")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            buildConfigField("boolean", "VERIFY_HARNESS", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.getProperty("storeFile") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // "verify" is a release-equivalent build used for on-device verification:
        // same R8 + resource shrinking + release signing as `release`, plus the
        // automation harness (tddl:// deep link, fixture-host cleartext). It is
        // declared AFTER `release` because `initWith(getByName("release"))` copies
        // the build type as configured at that moment — declaring it earlier made
        // the variant silently unminified (9.1 MB vs 2.8 MB).
        // Is NOT debuggable: AGP silently disables R8 optimizations for debuggable
        // build types, which would make this variant a poor proxy for release. The
        // verification scripts therefore read state from logcat and the fixture
        // server's request log instead of `run-as`.
        create("verify") {
            initWith(getByName("release"))
            applicationIdSuffix = ".verify"
            signingConfig = if (keystoreProps.getProperty("storeFile") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "VERIFY_HARNESS", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
        // Uncompressed + 16 KB-aligned native libs (Android 15 page-size rule).
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        warningsAsErrors = false
        htmlReport = true
        xmlReport = true
        baseline = file("lint-baseline.xml").takeIf { it.exists() }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)


    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// ── Static analysis (detekt) ──────────────────────────────────────────────
// ignoreFailures: the report is a quality signal for the review, not a build gate;
// findings are triaged in EVIDENCE/static-analysis/. maxIssues=0 with no ignores
// would block on style noise in generated/UI code.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt.yml"))
    source.setFrom(files("src/main/java", "src/test/java"))
    parallel = true
    ignoreFailures = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        txt.required.set(false)
    }
}

/**
 * Name the artifacts after the app instead of Gradle's default `app-release.apk`:
 *   DDownloader-<version>-<code>-release.apk / -debug.apk / -verify.apk
 * The version inside the file name is what a user sees when they download it, so it
 * has to match the release they are installing.
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // Public VariantOutput has no file-name setter in AGP 8.x; the internal impl
            // exposes it as a Property. This only affects the artifact's file name.
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("DDownloader-$appVersionName-$appVersionCode-${variant.name}.apk")
        }
    }
}
