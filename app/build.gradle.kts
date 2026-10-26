import java.util.Properties
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.minhatv"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.minhatv"
        minSdk = 21
        targetSdk = 34
        versionCode = 11
        versionName = "1.10"

        // lista embutida (lista.properties na raiz do projeto), embaralhada para não ficar em texto aberto no APK
        val lista = Properties().apply { rootProject.file("lista.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        fun emb(v: String?): String {
            val b = (v ?: "").trim().toByteArray(Charsets.UTF_8)
            val k = "brtv-0f3a9".toByteArray()
            val x = ByteArray(b.size) { i -> (b[i].toInt() xor k[i % k.size].toInt()).toByte() }
            return Base64.getEncoder().encodeToString(x)
        }
        buildConfigField("String", "L_SRV", "\"" + emb(lista.getProperty("servidor")) + "\"")
        buildConfigField("String", "L_USR", "\"" + emb(lista.getProperty("usuario")) + "\"")
        buildConfigField("String", "L_PWD", "\"" + emb(lista.getProperty("senha")) + "\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("minhatv.jks")
            storePassword = "minhatv123"
            keyAlias = "minhatv"
            keyPassword = "minhatv123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
