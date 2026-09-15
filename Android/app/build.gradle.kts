plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Macht `SettlementSelection` Parcelable — Voraussetzung fuer `rememberSaveable`.
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "at.heuriger.kassa"
    compileSdk = 37

    defaultConfig {
        applicationId = "at.heuriger.kassa"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("de")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests {
            // Robolectric braucht die gemergten Ressourcen und das Manifest.
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        // `MigrationTestHelper` liest die exportierten Schemata aus den Assets, und
        // Robolectric sieht nur die gemergten Assets der Variante unter Test — ein
        // Eintrag im Test-Sourceset bliebe unsichtbar. Deshalb am Debug-Sourceset:
        // in den Release-Build kommen die Schemata damit nicht.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Ohne exportiertes Schema laesst sich eine spaetere Room-Migration nicht pruefen.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
}

// MARK: - Vertragstests

/**
 * Faehrt die JVM-Unit-Tests gegen den laufenden Vapor-Server.
 *
 * `KASSA_CONTRACT_REQUIRED=1` sorgt dafuer, dass eine fehlende URL ein harter
 * Fehler ist statt eines stillen Skips — ohne das koennte diese Task gruen
 * melden, ohne je den Server beruehrt zu haben.
 */
val contractTest = tasks.register("contractTest") {
    group = "verification"
    description = "Vertragstests gegen den laufenden Kassa-Server (Standard: http://127.0.0.1:8080)."
    dependsOn("testDebugUnitTest")
}

tasks.withType<Test>().configureEach {
    val runsContractTests = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "contractTest" }
    if (runsContractTests) {
        environment(
            "KASSA_CONTRACT_URL",
            providers.gradleProperty("kassaContractUrl").orNull
                ?: System.getenv("KASSA_CONTRACT_URL")
                ?: "http://127.0.0.1:8080",
        )
        environment("KASSA_CONTRACT_REQUIRED", "1")
        // Der Serverzustand ist kein Task-Input; ohne das liefe die Task nach
        // einer Aenderung am Server faelschlich als UP-TO-DATE durch.
        outputs.upToDateWhen { false }
    }
}
