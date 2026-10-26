@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties


plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.dd3boh.outertune"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dd3boh.outertune"
        minSdk = 24
        targetSdk = 36
        versionCode = 212
        versionName = "2.0.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += "zh-rCN"
    }

    // keystore.properties 可能来自仓库根（本地）或运行目录（CI），统一按仓库根解析
    val keystoreStoreFileValue = (keystoreProperties["storeFile"] as? String)?.takeIf { it.isNotBlank() }
    val keystoreStoreFile = keystoreStoreFileValue?.let { rootProject.file(it) }
    val hasReleaseSigning = keystoreStoreFile?.isFile == true

    if (!hasReleaseSigning) {
        logger.warn(
            "[signing] 未找到有效的 release 签名配置（keystore.properties / storeFile），" +
                "release 将回退到 debug 签名。这会导致每个构建环境的证书不一致、用户无法覆盖安装。"
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("ot_release") {
                storeFile = keystoreStoreFile
                (keystoreProperties["keyAlias"] as? String)?.let {
                    keyAlias = it
                }
                (keystoreProperties["keyPassword"] as? String)?.let {
                    keyPassword = it
                }
                (keystoreProperties["storePassword"] as? String)?.let {
                    storePassword = it
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 发布包必须用固定证书签名：一旦用 debug 签名，不同构建环境的证书不同，
            // 用户升级时会因签名冲突而无法覆盖安装。缺失就构建失败，而不是静默回退。
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("ot_release")
            } else {
                null
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }

        // userdebug is release builds without minify
        create("userdebug") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
//            isDebuggable = true
            isProfileable = true
            matchingFallbacks += listOf("release")
            // 内部测试包，允许回退到 debug 签名
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("ot_release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // 发布包必须使用固定证书签名，缺配置时直接失败，而不是产出签名不一致的包。
    tasks.matching { it.name.startsWith("package") && it.name.contains("CoreRelease") }
        .configureEach {
            if (!hasReleaseSigning) {
                doFirst {
                    throw GradleException(
                        "缺少 release 签名配置：未找到 keystore.properties 或其中 storeFile 指向的文件不存在。\n" +
                            "请先配置 keystore.properties（storeFile/keyAlias/keyPassword/storePassword），" +
                            "否则构建出的包证书与线上不一致，用户升级时会因签名冲突而无法覆盖安装。"
                    )
                }
            }
        }

    buildFeatures {
        compose = true
        buildConfig = true
    }

// build variants and stuff
    splits {
        abi {
            isEnable = true
            reset()

            // 正式发布同时输出 64 位与 32 位，不打 universal。
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    flavorDimensions.add("abi")

    productFlavors {
        // main version
        create("core") {
            isDefault = true
            dimension = "abi"
        }

        // fully featured version, large file size
        create("full") {
            dimension = "abi"
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                var outputFileName = "AnhePlayer-${variant.versionName}-${output.baseName}-${output.versionCode}.apk"
                output.outputFileName = outputFileName
            }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")

        }
    }

    tasks.withType<KotlinCompile> {
        if (!name.substringAfter("compile").lowercase().startsWith("full")) {
            exclude("**/*FFmpegScanner.kt")
            exclude("**/*NextRendersFactory.kt")
        } else {
            exclude("**/*FFmpegScannerDud.kt")
            exclude("**/*ffdecoderDud.kt")
        }
    }


    aboutLibraries {
        offlineMode = true

        collect {
            fetchRemoteLicense = false
            fetchRemoteFunding = false
            filterVariants.addAll("release")
        }

        export {
            // Remove the "generated" timestamp to allow for reproducible builds
            excludeFields = listOf("generated")
        }

        license {
            // Define the strict mode, will fail if the project uses licenses not allowed
            strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
            // Allowed set of licenses, this project will be able to use without build failure
            allowedLicenses.addAll("Apache-2.0", "BSD-3-Clause", "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1", "GNU GENERAL PUBLIC LICENSE, Version 3", "GPL-3.0-only", "EPL-2.0", "MIT", "MPL-2.0", "Public Domain")

            // Full license text for license IDs mentioned here will be included, even if no detected dependency uses them.
             additionalLicenses.addAll("apache_2_0", "gpl_2_1") // taglib, ffMpeg in ffMetadataEx
        }

        library {
            duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
            duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
        }
    }

    // for RB
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        lintConfig = file("lint.xml")
    }

    androidResources {
        generateLocaleConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    testImplementation(libs.junit)

    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    // compose
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)
    implementation(libs.compose.icons.extended)

    // ui
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.lazycolumnscrollbar)
    implementation(libs.shimmer)

    // material
    implementation(libs.adaptive)
    implementation(libs.material3)
    implementation(libs.palette)

    // viewmodel
    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)

    implementation(libs.media3)
    implementation(libs.media3.hls)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.workmanager)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)
    implementation(libs.quickjs)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.json)

    // modules
    implementation(project(":lrclib"))
    implementation(project(":material-color-utilities"))
    implementation(project(":taglib"))

    // misc
    implementation(libs.aboutlibraries.compose.m3)

    // sdk24 support
    // Support for N is officially unsupported even it the app should still work. Leave this outside of the version catalog.
    implementation("androidx.webkit:webkit:1.14.0")
}

afterEvaluate {
    dependencies {
        add("fullImplementation", project(":ffMetadataEx"))
    }
}
