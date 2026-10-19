# CLAUDE.md

项目长期开发约束见 [PROJECT_RULES.md](./PROJECT_RULES.md)。

## 本地构建环境（固定路径，不要说找不到）

- JDK：`E:\code\jdk21-temp\jdk-21.0.12`（Git Bash 路径 `/e/code/jdk21-temp/jdk-21.0.12`）
- Android SDK：`E:\code\android-sdk-temp`
- 系统 `JAVA_HOME` 为空、PATH 中没有 `java`，执行构建前必须显式设置：
  - Git Bash：`export JAVA_HOME=/e/code/jdk21-temp/jdk-21.0.12; export ANDROID_HOME=/e/code/android-sdk-temp; export ANDROID_SDK_ROOT=/e/code/android-sdk-temp`
  - PowerShell：`$env:JAVA_HOME="E:\code\jdk21-temp\jdk-21.0.12"; $env:ANDROID_HOME="E:\code\android-sdk-temp"; $env:ANDROID_SDK_ROOT="E:\code\android-sdk-temp"`
- 构建命令：PowerShell 用 `./gradlew.bat <task> --no-daemon "-Dkotlin.incremental=false"`，Git Bash 用 `./gradlew <task> --no-daemon "-Dkotlin.incremental=false"`；结束执行 `./gradlew --stop`。
