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

        // 故意用 26 而不是 35。隐藏接口 createInsecureL2capSocket 属于 max-target-o 级别，
        // 只有 targetSdk <= 26 的应用才被允许通过反射调用它。实测确认这一招在本机有效，
        // 但拿到的 socket 连接 PSM 0x1001 时会死锁，所以 AAP 路线最终仍走不通——保留它只是
        // 为了诊断页能如实报告。代价是不能上架应用市场，侧载不受影响。
        targetSdk = 26

        versionCode = 10
        versionName = "1.9"
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
