import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名信息从 keystore.properties 读取(已 gitignore,不入库)。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists()

android {
    namespace = "com.example.airpodsbattery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.airpodsbattery"
        minSdk = 26

        // 曾经为了反射调用隐藏的 createInsecureL2capSocket（需要 targetSdk <= 26）压到 26，
        // 但实测那条路会死锁在蓝牙栈里，AAP 路线已放弃，保留低 targetSdk 没有意义了。
        // 现在改成和 CAPod 一致的新权限模型——它是能正常读到电量的实现。
        targetSdk = 35

        versionCode = 11
        versionName = "2.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                out.outputFileName = "airpods-battery-${versionName}.apk"
            }
        }
    }

    lint {
        // targetSdk 26 会触发 ExpiredTargetSdkVersion，那正是有意为之的。
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
