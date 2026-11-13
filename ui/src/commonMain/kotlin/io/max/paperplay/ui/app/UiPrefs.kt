package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }
enum class NavLayout(val label: String, val hint: String) {
    Pill("Pill", "The selected item expands to show its name."),
    Icons("Icons", "Icons only."),
    Labels("Labels", "Each icon has its name under it."),
    Dock("Dock", "Like Labels, but the bar spans the whole width."),
}
enum class SelectionLook(val label: String) { Fill("Fill"), Ring("Ring"), Soft("Soft"), Dot("Dot"), Text("Text") }
enum class IconSet(val label: String) {
    MaterialExpressive("Material 3 Expressive"),
    Tabler("Tabler"),
}
enum class LookGroup(val label: String, val hint: String) {
    Nav("Navigation bar", "The bottom bar, or the sidebar on desktop"),
    Bars("Top bars", "App bars at the top of pages"),
    Cards("Cards", "Cards and tiles"),
    Overlays("Dialogs, sheets and menus", "Anything that opens over a page"),
    Player("Player bar", "The bar along the bottom of a desktop window"),
}
enum class Look { Material, Glass }

class AccentOption(val id: String, val label: String, val color: Int)

val accentOptions = listOf(
    AccentOption("violet", "Violet", 0xFF7C5CFF.toInt()),
    AccentOption("ocean", "Ocean", 0xFF2F80ED.toInt()),
    AccentOption("mint", "Mint", 0xFF2FBF8F.toInt()),
    AccentOption("amber", "Amber", 0xFFE8A33D.toInt()),
    AccentOption("coral", "Coral", 0xFFF2643D.toInt()),
    AccentOption("rose", "Rose", 0xFFE8487F.toInt()),
    AccentOption("slate", "Slate", 0xFF7B8CA8.toInt()),
)

/** Look and layout choices, stored under the same pref keys as the Flutter app so a restored backup carries over. */
class UiPrefs(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    /** A saved text value by key, for small per-screen choices such as one playlist's sort order. */
    fun getString(key: String): String? = read(key)
    fun putString(key: String, value: String) = write(key, value)
    /** How song lists are ordered, shared by every list and kept between launches. */
    var songSort by mutableStateOf(io.max.paperplay.ui.logic.SongSort.entries.firstOrNull { it.name == read("song_sort") } ?: io.max.paperplay.ui.logic.SongSort.Added)
        private set
    var songSortReverse by mutableStateOf(read("song_sort_rev") == "true")
        private set

    fun setSongSort(sort: io.max.paperplay.ui.logic.SongSort, reverse: Boolean) {
        songSort = sort; songSortReverse = reverse
        write("song_sort", sort.name); write("song_sort_rev", "$reverse")
    }

    var themeMode by mutableStateOf(ThemeMode.entries.firstOrNull { it.name.lowercase() == read("theme_mode") } ?: ThemeMode.System)
        private set
    var amoled by mutableStateOf(read("theme_amoled") != "false")
        private set
    /** "dynamic", "cover" or an accent id. */
    var accent by mutableStateOf(read("theme_accent") ?: "dynamic")
        private set
    var floatingNav by mutableStateOf(read("floating_nav") != "false")
        private set
    /** The page background. Older installs only stored cover_gradient: absent or "true" is Cover, "false" is Plain. */
    var background by mutableStateOf(
        BgMode.entries.firstOrNull { it.name.lowercase() == read("bg_mode") } ?: if (read("cover_gradient") == "false") BgMode.Plain else BgMode.Cover
    )
        private set
    val coverGradient: Boolean get() = background == BgMode.Cover || background == BgMode.CoverBlur
    /** Standby mode settings (standby_* keys). */
    val standby = StandbyPrefs(read, write)
    private fun int(key: String, def: Int) = read(key)?.toIntOrNull() ?: def
    private fun flt(key: String, def: Float) = read(key)?.toFloatOrNull() ?: def
    var bgGradC1 by mutableStateOf(int("bg_grad_c1", 0xFF7C5CFF.toInt()))
        private set
    var bgGradC2 by mutableStateOf(int("bg_grad_c2", 0xFF101820.toInt()))
        private set
    /** Third gradient stop as ARGB; 0 means none. */
    var bgGradC3 by mutableStateOf(int("bg_grad_c3", 0))
        private set
    var bgGradAngle by mutableStateOf(flt("bg_grad_angle", 90f))
        private set
    var bgGradCover by mutableStateOf(read("bg_grad_cover") == "true")
        private set
    var bgImage by mutableStateOf(read("bg_image")?.takeIf { it.isNotEmpty() })
        private set
    var bgBlur by mutableStateOf(flt("bg_blur", 16f))
        private set
    var bgDim by mutableStateOf(flt("bg_dim", 0.35f))
        private set
    var bgAnim by mutableStateOf(BgAnim.entries.firstOrNull { it.name.lowercase() == read("bg_anim") } ?: BgAnim.Stars)
        private set
    var bgAnimC1 by mutableStateOf(int("bg_anim_c1", 0xFFFFFFFF.toInt()))
        private set
    var bgAnimC2 by mutableStateOf(int("bg_anim_c2", 0xFF0B1026.toInt()))
        private set
    var bgAnimSpeed by mutableStateOf(flt("bg_anim_speed", 1f))
        private set
    var bgAnimDensity by mutableStateOf(flt("bg_anim_density", 0.5f))
        private set
    var nowPlayingPanel by mutableStateOf(read("now_playing_panel") != "false")
        private set
    /** Transitions become instant (see [io.max.paperplay.ui.theme.LocalReduceMotion]). */
    var reduceMotion by mutableStateOf(read("reduce_motion") == "true")
        private set
    var profileName by mutableStateOf(read("profile_name") ?: "Listener")
        private set
    var profilePhoto by mutableStateOf(read("profile_photo")?.takeIf { it.isNotEmpty() })
        private set
    var iconSet by mutableStateOf(IconSet.entries.firstOrNull { it.name.lowercase() == read("icon_set") } ?: IconSet.MaterialExpressive)
        private set

    private val nav0 = runCatching { Json.parseToJsonElement(read("nav_style").orEmpty()).jsonObject }.getOrNull()
    var navLayout by mutableStateOf(NavLayout.entries.firstOrNull { it.name.lowercase() == nav0?.str("layout") } ?: NavLayout.Pill)
        private set
    var barRadius by mutableStateOf(nav0?.num("barRadius") ?: 100f)
        private set
    var selectionRadius by mutableStateOf(nav0?.num("selectionRadius") ?: 100f)
        private set
    var selectionLook by mutableStateOf(SelectionLook.entries.firstOrNull { it.name.lowercase() == nav0?.str("look") } ?: SelectionLook.Fill)
        private set

    var cornerRadius by mutableStateOf(read("nav_radius")?.toFloatOrNull() ?: nav0?.num("barRadius") ?: 100f)
        private set

    /** Desktop sidebar width in dp when expanded. */
    var sidebarWidth by mutableStateOf(read("sidebar_width")?.toFloatOrNull()?.coerceIn(180f, 320f) ?: 240f)
        private set
    fun putSidebarWidth(v: Float) { sidebarWidth = v; write("sidebar_width", "$v") }

    /** Desktop glass can blur the page behind each part, which is heavy; off keeps the tint and rim only. */
    var desktopBlur by mutableStateOf(read("glass_desktop_blur") == "true")
        private set
    fun putDesktopBlur(v: Boolean) { desktopBlur = v; write("glass_desktop_blur", "$v") }

    /** Desktop player bar: corner size (0..100), a solid card behind it when the look is Material, and hidden buttons. */
    var playerRadius by mutableStateOf(read("player_radius")?.toFloatOrNull()?.coerceIn(0f, 100f) ?: 40f)
        private set
    fun putPlayerRadius(v: Float) { playerRadius = v; write("player_radius", "$v") }
    /** Format badge (FLAC, Hi-Res, Dolby) on the desktop mini player. */
    var miniFormat by mutableStateOf(read("mini_format") != "false")
    fun putMiniFormat(v: Boolean) { miniFormat = v; write("mini_format", "$v") }
    var playerCard by mutableStateOf(read("player_card") == "true")
        private set
    fun putPlayerCard(v: Boolean) { playerCard = v; write("player_card", "$v") }
    private var playerHidden by mutableStateOf(read("player_hidden").orEmpty().split(',').filter { it.isNotBlank() }.toSet())
    fun playerButton(id: String): Boolean = id !in playerHidden
    fun setPlayerButton(id: String, on: Boolean) {
        playerHidden = if (on) playerHidden - id else playerHidden + id
        write("player_hidden", playerHidden.joinToString(","))
    }

    val cornerScale: Float get() = (cornerRadius / 100f).coerceIn(0f, 1f)

    private val glass0 = runCatching { Json.parseToJsonElement(read("glass_style").orEmpty()).jsonObject }.getOrNull()
    var glass by mutableStateOf(GlassStyle().let { d ->
        fun bool(k: String, def: Boolean) = (glass0?.get(k) as? JsonPrimitive)?.booleanOrNull ?: def
        GlassStyle(
            blur = glass0?.num("blur") ?: d.blur, lensHeight = glass0?.num("lensHeight") ?: d.lensHeight,
            lensAmount = glass0?.num("lensAmount") ?: d.lensAmount, depth = bool("depth", d.depth), chromatic = bool("chromatic", d.chromatic),
            saturation = glass0?.num("saturation") ?: d.saturation, tint = glass0?.num("tint") ?: d.tint,
            edgeWidth = glass0?.num("edgeWidth") ?: d.edgeWidth, edgeAlpha = glass0?.num("edgeAlpha") ?: d.edgeAlpha,
            edgeStyle = glass0?.num("edgeStyle")?.toInt() ?: d.edgeStyle, shadowRadius = glass0?.num("shadowRadius") ?: d.shadowRadius,
            shadowAlpha = glass0?.num("shadowAlpha") ?: d.shadowAlpha, innerRadius = glass0?.num("innerRadius") ?: d.innerRadius,
            innerAlpha = glass0?.num("innerAlpha") ?: d.innerAlpha,
        )
    })
        private set
    val glassBlur: Float get() = glass.blur
    val glassTint: Float get() = glass.tint

    val looks = mutableStateMapOf<LookGroup, Look>().apply {
        for (g in LookGroup.entries) {
            val saved = read("look_${g.name.lowercase()}")
            put(g, Look.entries.firstOrNull { it.name.lowercase() == saved } ?: if (g == LookGroup.Nav) Look.Glass else Look.Material)
        }
    }

    fun look(g: LookGroup): Look = looks[g] ?: Look.Material

    fun flag(key: String, default: Boolean): Boolean = read(key)?.let { it == "true" } ?: default
    fun putFlag(key: String, on: Boolean) = write(key, "$on")
    fun putProfileName(name: String) { profileName = name; write("profile_name", name) }
    fun putProfilePhoto(path: String?) { profilePhoto = path; write("profile_photo", path.orEmpty()) }
    fun putIconSet(set: IconSet) { iconSet = set; write("icon_set", set.name.lowercase()) }
    fun putThemeMode(m: ThemeMode) { themeMode = m; write("theme_mode", m.name.lowercase()) }
    fun putAmoled(on: Boolean) { amoled = on; write("theme_amoled", "$on") }
    fun putAccent(id: String) { accent = id; write("theme_accent", id) }
    fun putFloatingNav(on: Boolean) { floatingNav = on; write("floating_nav", "$on") }
    fun putCoverGradient(on: Boolean) = putBackground(if (on) BgMode.Cover else BgMode.Plain)
    fun putBackground(m: BgMode) { background = m; write("bg_mode", m.name.lowercase()); write("cover_gradient", "${m != BgMode.Plain}") }
    fun putBgGrad(c1: Int = bgGradC1, c2: Int = bgGradC2, c3: Int = bgGradC3, angle: Float = bgGradAngle, cover: Boolean = bgGradCover) {
        bgGradC1 = c1; bgGradC2 = c2; bgGradC3 = c3; bgGradAngle = angle; bgGradCover = cover
        write("bg_grad_c1", "$c1"); write("bg_grad_c2", "$c2"); write("bg_grad_c3", "$c3"); write("bg_grad_angle", "$angle"); write("bg_grad_cover", "$cover")
    }
    fun putBgImage(path: String?, blur: Float = bgBlur, dim: Float = bgDim) {
        bgImage = path; bgBlur = blur; bgDim = dim
        write("bg_image", path.orEmpty()); write("bg_blur", "$blur"); write("bg_dim", "$dim")
    }
    fun putBgAnim(anim: BgAnim = bgAnim, c1: Int = bgAnimC1, c2: Int = bgAnimC2, speed: Float = bgAnimSpeed, density: Float = bgAnimDensity) {
        bgAnim = anim; bgAnimC1 = c1; bgAnimC2 = c2; bgAnimSpeed = speed; bgAnimDensity = density
        write("bg_anim", anim.name.lowercase()); write("bg_anim_c1", "$c1"); write("bg_anim_c2", "$c2")
        write("bg_anim_speed", "$speed"); write("bg_anim_density", "$density")
    }
    fun putNowPlayingPanel(on: Boolean) { nowPlayingPanel = on; write("now_playing_panel", "$on") }
    fun putReduceMotion(on: Boolean) { reduceMotion = on; write("reduce_motion", "$on") }
    fun setLook(g: LookGroup, l: Look) { looks[g] = l; write("look_${g.name.lowercase()}", l.name.lowercase()) }

    fun putCornerRadius(value: Float) {
        cornerRadius = value
        write("nav_radius", "$value")
        setNav(bar = value, selection = value)
    }

    fun setNav(layout: NavLayout = navLayout, bar: Float = barRadius, selection: Float = selectionRadius, look: SelectionLook = selectionLook) {
        navLayout = layout; barRadius = bar; selectionRadius = selection; selectionLook = look
        write("nav_style", buildJsonObject {
            put("layout", layout.name.lowercase()); put("barRadius", bar.toDouble())
            put("selectionRadius", selection.toDouble()); put("look", look.name.lowercase())
        }.toString())
    }

    fun updateGlass(g: GlassStyle) {
        glass = g
        // Keeps the other glass fields a restored backup may hold.
        val old = runCatching { Json.parseToJsonElement(read("glass_style").orEmpty()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        fun n(v: Float) = JsonPrimitive(v.toDouble())
        write("glass_style", JsonObject(old + mapOf(
            "blur" to n(g.blur), "lensHeight" to n(g.lensHeight), "lensAmount" to n(g.lensAmount),
            "depth" to JsonPrimitive(g.depth), "chromatic" to JsonPrimitive(g.chromatic),
            "saturation" to n(g.saturation), "tint" to n(g.tint), "edgeWidth" to n(g.edgeWidth), "edgeAlpha" to n(g.edgeAlpha),
            "edgeStyle" to JsonPrimitive(g.edgeStyle), "shadowRadius" to n(g.shadowRadius), "shadowAlpha" to n(g.shadowAlpha),
            "innerRadius" to n(g.innerRadius), "innerAlpha" to n(g.innerAlpha),
        )).toString())
    }

    /** The accent as ARGB, or null for dynamic and cover colours. */
    val seed: Int? get() = accentOptions.firstOrNull { it.id == accent }?.color
}

/** Liquid glass material. Distances in dp. edgeStyle: 0 directional, 1 ambient, 2 plain. */
data class GlassStyle(
    val blur: Float = 8f,
    val lensHeight: Float = 12f,
    val lensAmount: Float = 24f,
    val depth: Boolean = false,
    val chromatic: Boolean = false,
    val saturation: Float = 1.5f,
    val tint: Float = 0.35f,
    val edgeWidth: Float = 1f,
    val edgeAlpha: Float = 1f,
    val edgeStyle: Int = 0,
    val shadowRadius: Float = 16f,
    val shadowAlpha: Float = 0.12f,
    val innerRadius: Float = 16f,
    val innerAlpha: Float = 0f,
)

private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.num(key: String): Float? = (get(key) as? JsonPrimitive)?.doubleOrNull?.toFloat()

val LocalUiPrefs = staticCompositionLocalOf<UiPrefs?> { null }

/** True when [group] is set to the glass look. */
@Composable
fun glassLook(group: LookGroup): Boolean = LocalUiPrefs.current?.look(group) == Look.Glass

/** Scaled rounded corner shape based on the unified corner radius preference setting. */
@Composable
fun appShape(base: Dp): RoundedCornerShape {
    val scale = LocalUiPrefs.current?.cornerScale ?: 1f
    return remember(base, scale) { RoundedCornerShape(base * scale) }
}

private val rimColor = Color.White.copy(alpha = 0.18f)

/**
 * A card, bar or overlay surface. Glass: the page background layer blurred behind a tint (real blur where a layer
 * and Android 12+ or desktop exist), else a tint with enough opacity to keep text readable. Material: solid tonal colour.
 * Blur and tint come from the Glass settings page and apply to every glass part.
 */
@Composable
fun Modifier.partSurface(group: LookGroup, color: Color, shape: Shape): Modifier {
    val prefs = LocalUiPrefs.current
    if (prefs == null || prefs.look(group) != Look.Glass) return clip(shape).background(color)
    val backdrop = io.max.paperplay.ui.LocalGlassBackdrop.current
    val reduced = io.max.paperplay.ui.theme.LocalReducedTransparency.current
    val alpha = surfaceAlpha(prefs.glassTint)
    val blurDp = prefs.glassBlur.dp
    return if (backdrop != null && !reduced && (io.max.paperplay.ui.isPhone || prefs.desktopBlur)) {
        clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { vibrancy(); blur(blurDp.toPx()) },
                onDrawSurface = { drawRect(color.copy(alpha = alpha)) },
            )
            .border(1.dp, rimColor, shape)
    } else {
        clip(shape).background(color.copy(alpha = if (reduced) 0.92f else maxOf(alpha, 0.6f))).border(1.dp, rimColor, shape)
    }
}

/** Tint 0..1 mapped so even the lowest setting keeps text readable on cards and bars. */
private fun surfaceAlpha(tint: Float): Float = 0.25f + tint.coerceIn(0f, 1f) * 0.7f

/** Dialog and menu colour for the Overlays part. Dialogs live in their own window, so they use the tint without a blur layer. */
@Composable
fun overlayColor(base: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Color {
    val prefs = LocalUiPrefs.current
    if (prefs == null || prefs.look(LookGroup.Overlays) != Look.Glass) return base
    // Menus are separate windows, so they cannot blur the page behind them; they stay nearly solid so the text behind never shows through.
    return base.copy(alpha = maxOf(surfaceAlpha(prefs.glassTint), 0.95f))
}

/** The light rim of a glass overlay; nothing for Material. */
@Composable
fun overlayRim(shape: Shape): Modifier = if (glassLook(LookGroup.Overlays)) Modifier.border(1.dp, rimColor, shape) else Modifier

