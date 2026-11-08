pluginManagement {
    repositories {
        google()
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}

rootProject.name = "taglib"
