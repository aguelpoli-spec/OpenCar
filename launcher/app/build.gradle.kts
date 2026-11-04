plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    testOptions { unitTests {
        isIncludeAndroidResources = true
        isReturnDefaultValues = true
        all { t -> System.getenv("ROBO_DIR")?.let { d -> t.systemProperty("robolectric.offline", "true"); t.systemProperty("robolectric.dependency.dir", d) } }
    } }
    namespace = "org.opencar.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.opencar.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 52
        versionName = "2.50"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    packaging { resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module") } }

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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    val mf = "0.21.0"
    implementation("org.mapsforge:mapsforge-core:$mf")
    implementation("org.mapsforge:mapsforge-map:$mf")
    implementation("org.mapsforge:mapsforge-map-reader:$mf")
    implementation("org.mapsforge:mapsforge-themes:$mf")
    implementation("org.mapsforge:mapsforge-map-android:$mf")
    implementation("com.caverock:androidsvg:1.4")
    implementation(files("libs/brouter-all.jar"))
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
