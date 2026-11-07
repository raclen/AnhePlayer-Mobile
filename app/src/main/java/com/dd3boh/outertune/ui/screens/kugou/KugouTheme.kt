package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** 酷狗页面沿用 MoeKoeMusic 的粉色与冷灰层级，不改动应用其他页面的主题。 */
@Composable
internal fun KugouTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.surface.luminance() < 0.5f
    val colors = base.copy(
        // 浅色交互文字使用更深的粉色，保证白底上可读；电台单独保留红色渐变。
        primary = if (dark) Color(0xFFFF7EB6) else Color(0xFFC92569),
        onPrimary = if (dark) Color(0xFF231018) else Color.White,
        primaryContainer = if (dark) Color(0xFF382330) else Color(0xFFFFE3F0),
        onPrimaryContainer = if (dark) Color(0xFFFFD9E9) else Color(0xFF75133F),
        surface = if (dark) Color(0xFF0E0F16) else Color(0xFFF7F7FA),
        surfaceContainerLowest = if (dark) Color(0xFF181A24) else Color.White,
        surfaceContainerLow = if (dark) Color(0xFF181A24) else Color.White,
        surfaceContainer = if (dark) Color(0xFF20222F) else Color(0xFFF1F2F6),
        surfaceContainerHigh = if (dark) Color(0xFF20222F) else Color(0xFFF1F2F6),
        surfaceContainerHighest = if (dark) Color(0xFF2C2F40) else Color(0xFFE9EBF2),
        onSurface = if (dark) Color(0xFFF4F5F9) else Color(0xFF12131F),
        onSurfaceVariant = if (dark) Color(0xFFA6ABBD) else Color(0xFF6E7386),
        outline = if (dark) Color(0xFF6F7488) else Color(0xFF9197A8),
        outlineVariant = if (dark) Color.White.copy(alpha = 0.07f) else Color(0xFF111827).copy(alpha = 0.06f),
    )
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.surface) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}
