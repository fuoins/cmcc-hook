plugins {
    id("com.android.application")
}

android {
    namespace = "com.qoder.cmcchook"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.qoder.cmcchook"
        minSdk = 23
        targetSdk = 30
        versionCode = 14
        versionName = "2.3"
    }

    signingConfigs {
        // 固定签名，保证 GitHub Actions 每次构建都能覆盖安装。
        // 仅用于本工具的调试分发，不是敏感密钥。
        create("stable") {
            storeFile = file("../keystore/cmcc-hook.jks")
            storePassword = "cmcchook2026"
            keyAlias = "cmcc"
            keyPassword = "cmcchook2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*", "META-INF/**")
        }
    }
}

dependencies {
    compileOnly(files("libs/XposedBridgeAPI-82.jar"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")
}
