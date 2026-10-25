package app.wallhop.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wallhop.R
import app.wallhop.ui.components.CircleButton
import app.wallhop.ui.components.InsetSection
import app.wallhop.ui.components.ListRow
import app.wallhop.ui.components.Overlays
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.WIcons
import app.wallhop.ui.design.Wallhop

/** Something Lync is built with, and its licence: bundled text, or a page to open. */
private class Credit(val name: String, val licence: String, val text: Int? = null, val url: String? = null)

private const val APACHE = "https://www.apache.org/licenses/LICENSE-2.0"

/** True when this build includes Nothing's Glyph SDK (it's optional; see android/app/libs/README.md). */
private fun hasGlyphSdk(): Boolean = runCatching { Class.forName("com.nothing.ketchum.Glyph") }.isSuccess

private fun credits(): List<Credit> = buildList {
    add(Credit("Figtree", "SIL Open Font License 1.1", text = R.raw.license_figtree))
    add(Credit("Doto", "SIL Open Font License 1.1", text = R.raw.license_doto))
    add(Credit("Galindo", "SIL Open Font License 1.1", text = R.raw.license_galindo))
    add(Credit("Phosphor Icons", "MIT License", text = R.raw.license_phosphor))
    add(Credit("AndroidX and Jetpack Compose", "Apache License 2.0", url = APACHE))
    add(Credit("ZXing", "Apache License 2.0", url = APACHE))
    add(Credit("Tink", "Apache License 2.0", url = APACHE))
    add(Credit("Backdrop and Shapes by Kyant", "Apache License 2.0", url = APACHE))
    add(Credit("Kotlin and kotlinx.coroutines", "Apache License 2.0", url = APACHE))
    if (hasGlyphSdk()) {
        add(Credit("Nothing Glyph Developer Kit", "Proprietary. © Nothing Technology Limited, used under Nothing's terms", url = "https://github.com/Nothing-Developer-Programme"))
    }
}

/** About › Open-source licences: what Lync is built with. Tap one to read its licence. */
@Composable
fun LicencesScreen(overlays: Overlays, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val credits = remember { credits() }
    Page(
        title = "Open-source licences",
        subtitle = "Lync is built with these fonts, icons and libraries.",
        leading = { CircleButton(WIcons.ChevronLeft, "Back", onClick = { onBack() }) },
    ) {
        item(key = "credits") {
            InsetSection {
                credits.forEach { credit ->
                    row {
                        ListRow(
                            title = credit.name,
                            subtitle = credit.licence,
                            chevron = true,
                            onClick = {
                                when {
                                    credit.text != null -> showLicence(overlays, context, credit.name, credit.text)
                                    credit.url != null -> openLink(context, credit.url)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun showLicence(overlays: Overlays, context: Context, name: String, res: Int) {
    val text = runCatching { context.resources.openRawResource(res).bufferedReader().use { reflow(it.readText()) } }.getOrDefault("")
    overlays.showSheet(title = name, large = true) {
        Text(
            text.trim(),
            style = Type.footnote,
            color = Wallhop.colors.labelSecondary,
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** Opens a web page; does nothing if the phone has no browser. */
internal fun openLink(context: Context, url: String) = app.wallhop.core.Open.link(context, url)

/**
 * Licence files are hard-wrapped at ~80 columns, which leaves ragged half-empty lines on a phone.
 * Joins the lines of each paragraph. Blank lines, list items, ruled lines and ALL-CAPS headings
 * stay on lines of their own.
 */
internal fun reflow(text: String): String {
    val listItem = Regex("""^([-*•]|\d+[.)]|\(\w\))\s""")
    val rule = Regex("""^[-=_]{3,}$""")
    fun standsAlone(t: String) = rule.matches(t) || (t.any { it.isLetter() } && t == t.uppercase())
    val lines = mutableListOf<String>()
    var joinable = false // whether the next line may be joined onto the last one
    for (raw in text.replace("\r\n", "\n").lines()) {
        val t = raw.trim()
        if (t.isNotEmpty() && joinable && !standsAlone(t) && !listItem.containsMatchIn(t)) {
            lines[lines.lastIndex] += " $t"
        } else {
            lines += t
            joinable = t.isNotEmpty() && !standsAlone(t)
        }
    }
    return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}
