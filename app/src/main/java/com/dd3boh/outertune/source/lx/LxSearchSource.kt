package com.dd3boh.outertune.source.lx

enum class LxSearchSource(
    val sourceId: String,
    val displayName: String,
) {
    KUGOU("kg", "酷狗"),
    NETEASE("wy", "网易云"),
    QQ("tx", "QQ 音乐"),
    KUWO("kw", "酷我"),
    QISHUI("qs", "汽水"),
}
