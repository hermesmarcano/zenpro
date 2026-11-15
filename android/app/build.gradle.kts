plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hermesmarcano.zen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hermesmarcano.zen"
        minSdk = 26
        targetSdk = 35
        // CI numera cada compilación: Android solo instala encima una versión con versionCode igual o mayor.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    // Clave SOLO para instalar por APK (sideload). Es pública a propósito: sirve para que
    // las actualizaciones se instalen encima de la versión anterior sin perder alarmas ni
    // anclas. NO es una clave de publicación: para Google Play se usa otra, privada, que
    // nunca debe entrar al repositorio.
    signingConfigs {
        create("sideload") {
            storeFile = file("zen-sideload.jks")
            storePassword = "zen-sideload"
            keyAlias = "zen"
            keyPassword = "zen-sideload"
        }
    }

    buildTypes {
        release {
            // Sin R8 a propósito: no hay dispositivo donde probar un build ofuscado y un
            // fallo por reflexión sería silencioso hasta que suene (o no) la alarma.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
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
    testOptions {
        unitTests.all {
            // Las pruebas de pantallas dejan recompositores con relojes en bucle: cada clase en su JVM
            // evita que contaminen a las siguientes (Compose no llegaba nunca a "reposo").
            it.setForkEvery(1)
        }
        unitTests.isReturnDefaultValues = true
        // Robolectric necesita los recursos y el manifiesto para levantar actividades y servicios.
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    // En los tests de JVM `org.json` de Android es un stub vacío; esta es la implementación real.
    testImplementation("org.json:json:20240303")

    // Humo de la capa Android en JVM (no hay emulador): receptores, servicio, manifiesto y pantallas.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
