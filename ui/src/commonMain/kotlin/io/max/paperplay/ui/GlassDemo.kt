package io.max.paperplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.clickable

private val tabs = listOf("Home", "Search", "Library", "Settings")

private fun cover(i: Int): Brush {
    val a = Color.hsv((i * 47f) % 360f, 0.75f, 0.95f)
    val b = Color.hsv((i * 47f + 60f) % 360f, 0.85f, 0.6f)
    return Brush.linearGradient(listOf(a, b))
}

@Composable
fun GlassDemo() {
    val backdrop = rememberLayerBackdrop()
    var tab by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.fillMaxSize().layerBackdrop(backdrop),
            contentPadding = PaddingValues(16.dp, 48.dp, 16.dp, 200.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items((0 until 40).toList()) { i ->
                Box(
                    Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(cover(i)).padding(12.dp),
                    contentAlignment = Alignment.BottomStart,
                ) { Text("Album ${i + 1}", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassSurface(backdrop, Modifier.fillMaxWidth().height(64.dp)) {
                Column {
                    Row(Modifier.weight(1f).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(cover(3)))
                        Text("Now Playing Title", Modifier.weight(1f).padding(start = 12.dp), color = Color.White, maxLines = 1)
                        Text(if (playing) "II" else ">", color = Color.White, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { playing = !playing }.padding(12.dp))
                    }
                    Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(0.4f).fillMaxHeight().background(Color.White))
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            GlassSurface(backdrop, Modifier.fillMaxWidth().height(64.dp), cornerRadius = 32.dp) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEachIndexed { i, t ->
                        Box(Modifier.weight(1f).fillMaxHeight().clickable { tab = i }, contentAlignment = Alignment.Center) {
                            Text(t, color = Color.White.copy(alpha = if (tab == i) 1f else 0.6f),
                                fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}
