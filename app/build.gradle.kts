plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sparkhoward.nagomiani"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sparkhoward.nagomiani"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 弹幕凭据（弹弹play）：真实值不进仓库——仓库根目录 gitignored 的
    // DanmakuCredentials.private（两行：AppId/AppSecret）在构建期 XOR 0x5A
    // 混淆后写入 assets，应用启动时解码（与 mac 版 pack.sh 同构）。
    // 文件缺失时跳过（无凭据 → 弹幕功能静默停用）。
    val credsPrivate = rootProject.file("DanmakuCredentials.private")
    val credsAssets = file("src/main/assets").apply { mkdirs() }
    val credsBin = File(credsAssets, "danmaku-credentials.bin")
    if (credsPrivate.isFile) {
        tasks.register("generateDanmakuCredentials") {
            inputs.file(credsPrivate)
            outputs.file(credsBin)
            doLast {
                val payload = credsPrivate.readBytes().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
                credsBin.writeBytes(payload)
            }
        }
        tasks.named("preBuild") { dependsOn("generateDanmakuCredentials") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.database)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    debugImplementation(libs.androidx.ui.tooling)
}
