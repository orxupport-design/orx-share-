package com.orx.share.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val OrxBg = Color(0xFF0B1020)
val OrxSurface = Color(0xFF141B33)
val OrxViolet = Color(0xFF7C4DFF)
val OrxCyan = Color(0xFF00E5FF)
val OrxText = Color(0xFFE8ECFF)
val OrxMuted = Color(0xFF8E98BD)

private val Scheme = darkColorScheme(
    primary = OrxViolet,
    secondary = OrxCyan,
    background = OrxBg,
    surface = OrxSurface,
    onPrimary = Color.White,
    onSecondary = Color(0xFF00232A),
    onBackground = OrxText,
    onSurface = OrxText,
)

@Composable
fun OrxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme) {
        // Surface sets the default text color; without it text is black on the dark background.
        Surface(modifier = Modifier.fillMaxSize(), color = OrxBg, contentColor = OrxText, content = content)
    }
}
