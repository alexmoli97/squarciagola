import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Client ID di Spotify preso da local.properties, che non finisce nel repository.
 * In PKCE non e' un segreto, ma non c'e' motivo di digitarlo sul telefono a ogni
 * installazione: lo si mette una volta qui e il campo sparisce dalla schermata.
 */
val spotifyClientId: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("spotify.clientId")
    // Ricaduta sul valore committato in gradle.properties: cosi' una copia appena clonata
    // compila e accede senza configurazione, e chi vuole il proprio lo mette in
    // local.properties senza toccare il repository.
    ?.takeIf { it.isNotBlank() }
    ?: providers.gradleProperty("spotify.clientId").orNull.orEmpty()

android {
    namespace = "it.squarciagola"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.squarciagola"
        minSdk = 26
        targetSdk = 35
        // Il versionCode e' il numero nel tag della release GitHub (v2, v3, ...): e' quello
        // che l'app confronta per capire se c'e' un aggiornamento. Vedi UpdateChecker.
        versionCode = 25
        versionName = "0.25"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Redirect URI dell'OAuth PKCE: it.squarciagola://auth
        manifestPlaceholders["authScheme"] = "it.squarciagola"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Android Auto
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")

    // UI telefono
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
    // Implementazione reale di org.json: quella di Android nei test unitari e' solo uno stub.
    testImplementation("org.json:json:20240303")
}
