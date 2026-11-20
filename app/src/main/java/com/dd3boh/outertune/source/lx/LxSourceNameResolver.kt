package com.dd3boh.outertune.source.lx

/**
 * 洛雪音源名称解析器：
 * 保证音源显示名称和音源检测页面（cf-anheplayer /sources）完全一致，绝不使用文件名称或临时哈希。
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
        val head = code.take(10000)
        fun get(key: String): String? {
            val regex = Regex("""@$key[ \t]+([^\r\n*]+)""")
            return regex.find(head)?.groupValues?.getOrNull(1)?.trim()
        }

        var name = get("name")
        if (name == null) {
            val nameMatch = Regex("""name\s*:\s*['"]([^'"]+)['"]""").find(head)
            if (nameMatch != null) {
                name = nameMatch.groupValues.getOrNull(1)?.trim()
            }
        }

        var version = get("version")
        if (version == null) {
            val verMatch = Regex("""version\s*:\s*['"]([^'"]+)['"]""").find(head)
            if (verMatch != null) {
                version = verMatch.groupValues.getOrNull(1)?.trim()
            }
        }

        return Meta(
            name = name,
            version = version,
        )
    }

    /**
     * 解析音源显示名称：
     * 1. 优先匹配检测页面 remote.json 中的在线源标识
     * 2. 匹配脚本内部 @name 或 name 声明，并在 KNOWN_NAME_MAP 中对齐
     * 3. 兜底为“自定义音源”，绝不使用任何文件名称或临时哈希
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
        if (!meta.name.isNullOrBlank()) {
            if (KNOWN_NAME_MAP.containsKey(meta.name)) {
                return KNOWN_NAME_MAP[meta.name]!!
            }

            return if (!meta.version.isNullOrBlank() && !meta.name.contains(meta.version, ignoreCase = true)) {
                "${meta.name} v${meta.version.removePrefix("v").removePrefix("V")}"
            } else {
                meta.name
            }
        }

        return "自定义音源"
    }
}
