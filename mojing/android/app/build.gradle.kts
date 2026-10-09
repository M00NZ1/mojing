import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.variant.FilterConfiguration

/**
 * 语义化版本（`versionName` 为 `x.y.z`，不带 `v`；界面若要显示「v1.0.1」可自行拼接）。
 *
 * - **小版本**（日常迭代）
 *   - **补丁**：`1.0.0 → 1.0.1` — 修 bug、小改、兼容调整
 *   - **次版本**：`1.0.x → 1.1.0` — 新功能或较大更新，**次版本 +1，补丁位归零**
 * - **大版本**（里程碑 / 代际或不兼容）：`1.x.x → 2.0.0` — **主版本 +1，次版本与补丁归零**
 *
 * `versionCode` 与 `x.y.z` 对齐为 `major*10_000 + minor*100 + patch`（要求 `0≤minor≤99`、`0≤patch≤99`；
 * 超出时请改公式或改为手写整数，并保持单调递增）。
 */
private val appVersionMajor = 1
private val appVersionMinor = 2
private val appVersionPatch = 3

private val appVersionCode = appVersionMajor * 10_000 + appVersionMinor * 100 + appVersionPatch

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mojing.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mojing.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "$appVersionMajor.$appVersionMinor.$appVersionPatch"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        val props = Properties()
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) {
            props.load(FileInputStream(propsFile))
            val path = props.getProperty("KEYSTORE_PATH")?.trim().orEmpty()
            if (path.isNotEmpty()) {
                val keystoreFile = rootProject.file(path)
                if (keystoreFile.isFile) {
                    create("release") {
                        storeFile = keystoreFile
                        storePassword = props.getProperty("KEYSTORE_PASSWORD", "")
                        keyAlias = props.getProperty("KEY_ALIAS", "")
                        keyPassword = props.getProperty("KEY_PASSWORD", "")
                    }
                }
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            val fileName = when (abi) {
                null -> "MoJing-${appVersionMajor}.${appVersionMinor}.${appVersionPatch}-universal-release.apk"
                "arm64-v8a" -> "MoJing-${appVersionMajor}.${appVersionMinor}.${appVersionPatch}-arm64-v8a-release.apk"
                "armeabi-v7a" -> "MoJing-${appVersionMajor}.${appVersionMinor}.${appVersionPatch}-armeabi-v7a-release.apk"
                else -> null
            }
            if (fileName != null) {
                output as com.android.build.api.variant.impl.VariantOutputImpl
                output.outputFileName = fileName
            }
        }
    }
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.coil.compose)
    implementation(libs.richtext.commonmark)
    implementation(libs.richtext.ui.material3)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.room.testing)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}
