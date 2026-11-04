plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    testOptions { unitTests {
        isIncludeAndroidResources = true; isReturnDefaultValues = true
        all { t -> System.getenv("ROBO_DIR")?.let { d -> t.systemProperty("robolectric.offline", "true"); t.systemProperty("robolectric.dependency.dir", d) } }
    } }
    namespace = "org.opencar.music"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.opencar.music"
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "2.1"
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}
