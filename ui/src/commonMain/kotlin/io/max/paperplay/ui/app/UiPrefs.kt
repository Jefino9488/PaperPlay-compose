package io.max.paperplay.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
enum class LookGroup(val label: String, val hint: String) {
    Nav("Navigation bar", "The bottom bar"),
    Bars("Top bars", "App bars at the top of pages"),
    Cards("Cards", "Cards and tiles"),
    Overlays("Dialogs, sheets and menus", "Anything that opens over a page"),
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
    var themeMode by mutableStateOf(ThemeMode.entries.firstOrNull { it.name.lowercase() == read("theme_mode") } ?: ThemeMode.System)
        private set
    var amoled by mutableStateOf(read("theme_amoled") != "false")
        private set
    /** "dynamic", "cover" or an accent id. */
    var accent by mutableStateOf(read("theme_accent") ?: "dynamic")
        private set
    var floatingNav by mutableStateOf(read("floating_nav") != "false")
        private set
    var coverGradient by mutableStateOf(read("cover_gradient") == "true")
        private set
    var nowPlayingPanel by mutableStateOf(read("now_playing_panel") != "false")
        private set
    var profileName by mutableStateOf(read("profile_name") ?: "Listener")
        private set
    var profilePhoto by mutableStateOf(read("profile_photo")?.takeIf { it.isNotEmpty() })
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

    private val glass0 = runCatching { Json.parseToJsonElement(read("glass_style").orEmpty()).jsonObject }.getOrNull()
    var glassBlur by mutableStateOf(glass0?.num("blur") ?: 8f)
        private set
    var glassTint by mutableStateOf(glass0?.num("tint") ?: 0.35f)
        private set

    val looks = mutableStateMapOf<LookGroup, Look>().apply {
        for (g in LookGroup.entries) {
            val saved = read("look_${g.name.lowercase()}")
            put(g, Look.entries.firstOrNull { it.name.lowercase() == saved } ?: if (g == LookGroup.Nav) Look.Glass else Look.Material)
        }
    }

    fun look(g: LookGroup): Look = looks[g] ?: Look.Material

    fun putProfileName(name: String) { profileName = name; write("profile_name", name) }
    fun putThemeMode(m: ThemeMode) { themeMode = m; write("theme_mode", m.name.lowercase()) }
    fun putAmoled(on: Boolean) { amoled = on; write("theme_amoled", "$on") }
    fun putAccent(id: String) { accent = id; write("theme_accent", id) }
    fun putFloatingNav(on: Boolean) { floatingNav = on; write("floating_nav", "$on") }
    fun putCoverGradient(on: Boolean) { coverGradient = on; write("cover_gradient", "$on") }
    fun putNowPlayingPanel(on: Boolean) { nowPlayingPanel = on; write("now_playing_panel", "$on") }
    fun setLook(g: LookGroup, l: Look) { looks[g] = l; write("look_${g.name.lowercase()}", l.name.lowercase()) }

    fun setNav(layout: NavLayout = navLayout, bar: Float = barRadius, selection: Float = selectionRadius, look: SelectionLook = selectionLook) {
        navLayout = layout; barRadius = bar; selectionRadius = selection; selectionLook = look
        write("nav_style", buildJsonObject {
            put("layout", layout.name.lowercase()); put("barRadius", bar.toDouble())
            put("selectionRadius", selection.toDouble()); put("look", look.name.lowercase())
        }.toString())
    }

    fun setGlass(blur: Float = glassBlur, tint: Float = glassTint) {
        glassBlur = blur; glassTint = tint
        // Keeps the other glass fields a restored backup may hold.
        val old = runCatching { Json.parseToJsonElement(read("glass_style").orEmpty()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        write("glass_style", JsonObject(old + mapOf("blur" to JsonPrimitive(blur.toDouble()), "tint" to JsonPrimitive(tint.toDouble()))).toString())
    }

    /** The accent as ARGB, or null for dynamic and cover colours. */
    val seed: Int? get() = accentOptions.firstOrNull { it.id == accent }?.color
}

private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.num(key: String): Float? = (get(key) as? JsonPrimitive)?.doubleOrNull?.toFloat()
