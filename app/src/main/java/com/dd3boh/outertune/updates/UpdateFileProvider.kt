package com.dd3boh.outertune.updates

import androidx.core.content.FileProvider

/** 使用独立 Provider 类，避免与现有文件分享 Provider 的 Manifest 配置合并。 */
class UpdateFileProvider : FileProvider()
