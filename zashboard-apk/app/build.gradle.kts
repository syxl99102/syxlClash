plugins {
    id("com.android.application")
}

android {
    namespace = "com.syxl.zashboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.syxl.zashboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }
}
