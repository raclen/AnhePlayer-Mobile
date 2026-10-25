package com.dd3boh.outertune.radio

object RadioConstants {
    val BITRATE_OPTIONS = listOf(
        "all" to "全部码率",
        "128" to "≥ 128 kbps",
        "192" to "≥ 192 kbps",
        "320" to "320 kbps",
    )

    val COUNTRY_OPTIONS = listOf(
        "" to "全部国家和地区",
        "CN" to "中国 (China)",
        "HK" to "中国香港 (Hong Kong)",
        "TW" to "中国台湾 (Taiwan)",
        "JP" to "日本 (Japan)",
        "KR" to "韩国 (South Korea)",
        "US" to "美国 (United States)",
        "GB" to "英国 (United Kingdom)",
        "DE" to "德国 (Germany)",
        "FR" to "法国 (France)",
        "CA" to "加拿大 (Canada)",
        "AU" to "澳大利亚 (Australia)",
        "RU" to "俄罗斯 (Russia)",
        "SG" to "新加坡 (Singapore)",
        "MY" to "马来西亚 (Malaysia)",
        "TH" to "泰国 (Thailand)",
    )

    val LANGUAGE_OPTIONS = listOf(
        "" to "全部语言",
        "chinese" to "中文 (Chinese)",
        "cantonese" to "粤语 (Cantonese)",
        "english" to "英语 (English)",
        "japanese" to "日语 (Japanese)",
        "korean" to "韩语 (Korean)",
        "spanish" to "西班牙语 (Spanish)",
        "french" to "法语 (French)",
        "german" to "德语 (German)",
        "russian" to "俄语 (Russian)",
        "arabic" to "阿拉伯语 (Arabic)",
        "thai" to "泰语 (Thai)",
    )

    fun getCountryName(code: String?): String {
        if (code.isNullOrBlank()) return "全部国家和地区"
        return COUNTRY_OPTIONS.firstOrNull { it.first.equals(code, ignoreCase = true) }?.second
            ?: code.uppercase()
    }

    fun getLanguageName(lang: String?): String {
        if (lang.isNullOrBlank()) return "全部语言"
        return LANGUAGE_OPTIONS.firstOrNull { it.first.equals(lang, ignoreCase = true) }?.second
            ?: lang
    }

    fun getBitrateLabel(key: String?): String {
        return BITRATE_OPTIONS.firstOrNull { it.first == key }?.second ?: "全部码率"
    }

    fun getBitrateRange(key: String?): Pair<Int?, Int?> {
        return when (key) {
            "128" -> 128 to null
            "192" -> 192 to null
            "320" -> 320 to 320
            else -> null to null
        }
    }
}
