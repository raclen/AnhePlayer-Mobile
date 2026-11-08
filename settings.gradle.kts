@file:Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
}

rootProject.name = "AnhePlayer-Mobile"
include(":app")
include(":lrclib")
include(":material-color-utilities")
include(":ffMetadataEx")
include(":taglib")

includeBuild(file("media").toPath().toRealPath().toAbsolutePath().toString()) {
    dependencySubstitution {
        substitute(module("androidx.media3:media3-common")).using(project(":lib-common"))
        substitute(module("androidx.media3:media3-common-ktx")).using(project(":lib-common-ktx"))
        substitute(module("androidx.media3:media3-datasource-okhttp")).using(project(":lib-datasource-okhttp"))
        substitute(module("androidx.media3:media3-exoplayer")).using(project(":lib-exoplayer"))
        // 电台/网络源大量使用 HLS(.m3u8) 流，必须显式接入 exoplayer-hls 模块，
        // 否则 DefaultMediaSourceFactory 反射加载 HlsMediaSource$Factory 会抛 ClassNotFoundException
        substitute(module("androidx.media3:media3-exoplayer-hls")).using(project(":lib-exoplayer-hls"))
        substitute(module("androidx.media3:media3-exoplayer-workmanager")).using(project(":lib-exoplayer-workmanager"))
        substitute(module("androidx.media3:media3-session")).using(project(":lib-session"))
    }
}
