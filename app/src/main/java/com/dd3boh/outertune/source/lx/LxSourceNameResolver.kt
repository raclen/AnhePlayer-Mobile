package com.dd3boh.outertune.source.lx

/**
 * 洛雪音源名称解析器：
 * 保证音源显示名称和音源检测页面（cf-anheplayer /sources）完全一致，绝不显示文件系统/临时文件名。
 */
object LxSourceNameResolver {
    /** 对应 sources/remote.json 及音源检测页面中的远程音源标题 */
    private val REMOTE_TITLE_MAP = mapOf(
        "changqing" to "ChangQing（长青）",
        "flower" to "Flower",
        "grass" to "Grass",
        "huanyin" to "HuanYin（幻音）",
        "huibq" to "Huibq",
        "ikun" to "ikun",
        "juhe" to "JuheApi（聚合）",
        "lx" to "LX",
        "qdy" to "QDY",
        "sixyin" to "SixYin",
        "xinghai" to "XingHai（星海）",
    )

    /** 脚本内部 @name 与检测页面展示名称的已知映射 */
    private val KNOWN_NAME_MAP = mapOf(
        "野花🌷" to "Flower",
        "野草🌾" to "Grass",
        "长青SVIP音源" to "ChangQing（长青）",
        "六音音源" to "SixYin",
        "Huibq_lxmusic源" to "Huibq",
        "ikun音源" to "ikun",
        "豆包定制[聚合音源]" to "lx-source",
        "⭐maflya-lx-音源" to "maflya-lx-音源",
    )

    data class Meta(
        val name: String?,
        val version: String?,
    )

    fun parseMeta(code: String): Meta {
        val head = code.take(8000)
        fun get(key: String): String? {
            val regex = Regex("""@$key[ \t]+([^\r\n*]+)""")
            return regex.find(head)?.groupValues?.getOrNull(1)?.trim()
        }
        return Meta(
            name = get("name"),
            version = get("version"),
        )
    }

    /**
     * 解析音源显示名称：
     * 1. 优先匹配检测页面 remote.json 中的在线源标识
     * 2. 匹配脚本内部 @name 在检测页面的特定别名
     * 3. 匹配检测页面本地源标题（从 hintUrlOrName 提取非哈希的有效业务名）
     * 4. 提取脚本内部 @name 及 @version
     * 5. 兜底为“自定义音源”，绝不回退为 .js 文件名或 hash 临时文件名
     */
    fun resolve(code: String, hintUrlOrName: String = ""): String {
        val hint = hintUrlOrName.lowercase()

        // 1. 检查 remote 在线源关键词
        for ((k, title) in REMOTE_TITLE_MAP) {
            if (hint.contains("/$k.js") || hint.contains("/$k/") || hint.endsWith("/$k") ||
                hint.contains("remote-$k") || hint == k
            ) {
                return title
            }
        }

        val meta = parseMeta(code)

        // 2. 根据脚本元数据的 @name 映射到检测页面标题
        if (meta.name != null && KNOWN_NAME_MAP.containsKey(meta.name)) {
            return KNOWN_NAME_MAP[meta.name]!!
        }

        // 3. 从 hint 提取纯净名称尝试匹配检测页面标题（排除临时/哈希名称）
        val rawBase = hintUrlOrName
            .substringAfterLast('/')
            .substringAfterLast('\\')
        val cleanHint = if (rawBase.endsWith(".js", ignoreCase = true)) {
            rawBase.substring(0, rawBase.length - 3)
        } else {
            rawBase
        }.trim()

        val isLikelyHash = cleanHint.matches(Regex("""^[a-f0-9]{8,64}$""", RegexOption.IGNORE_CASE)) ||
                cleanHint.startsWith("document:", ignoreCase = true) ||
                cleanHint.startsWith("blob:", ignoreCase = true) ||
                cleanHint.startsWith("temp", ignoreCase = true)

        if (cleanHint.length > 1 && !isLikelyHash && !cleanHint.endsWith(".js", ignoreCase = true)) {
            return cleanHint
        }

        // 4. 使用脚本中的 @name
        if (!meta.name.isNullOrBlank()) {
            return if (!meta.version.isNullOrBlank() && !meta.name.contains(meta.version)) {
                "${meta.name} v${meta.version.removePrefix("v").removePrefix("V")}"
            } else {
                meta.name
            }
        }

        return "自定义音源"
    }
}
