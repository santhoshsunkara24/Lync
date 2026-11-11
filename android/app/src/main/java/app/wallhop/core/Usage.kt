package app.wallhop.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.wallhop.BuildConfig

/**
 * How Lync gets used, kept on this phone only, so a tester can choose to send it with feedback.
 *
 * Only counts of named events: which screens were opened, which features ran, which switches
 * changed, which problems were shown. Never a code, a message, a name, a number, a file or an
 * address. Nothing here leaves the phone on its own: [feedbackEmail] puts the report in an email
 * the user reads, can edit, and sends (or doesn't) from their own mail app.
 */
object Usage {
    /** Where feedback goes. The same address as the Play listing's contact email. */
    const val FEEDBACK_TO = "santhoshreddy1058@gmail.com"

    private const val PREFS = "usage"
    private const val FIRST_USE = "first_use"
    private const val PROBLEM_PREFIX = "problem:"
    @Volatile private var appContext: Context? = null

    /** Called once at start (the app and the link service both do). */
    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(FIRST_USE)) prefs.edit().putLong(FIRST_USE, System.currentTimeMillis()).apply()
    }

    /** One more of [event]: a short fixed name like "screen_home" or "code_to_mac". */
    fun count(event: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit().putInt(event, prefs.getInt(event, 0) + 1).apply()
    }

    /** A problem the app showed, by its title (titles are Lync's own fixed words). */
    fun problem(title: String) = count(PROBLEM_PREFIX + title.take(60))

    /** The report, in plain words, for the email body. */
    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = prefs.all.filterValues { it is Int }.mapValues { it.value as Int }
        val days = ((System.currentTimeMillis() - prefs.getLong(FIRST_USE, System.currentTimeMillis())) / 86_400_000L) + 1
        fun group(prefix: String): String = all.filterKeys { it.startsWith(prefix) }
            .toSortedMap().entries.joinToString(", ") { "${it.key.removePrefix(prefix).replace('_', ' ')} ${it.value}" }
            .ifEmpty { "none" }
        val switches = Features.Key.entries.joinToString(", ") { "${it.name} ${if (Features.on(context, it)) "on" else "off"}" }
        return buildString {
            appendLine("--- Usage report (counts only, kept on the phone until you send this) ---")
            appendLine("Lync ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Using Lync for: $days day${if (days == 1L) "" else "s"}")
            appendLine("Screens opened: ${group("screen_")}")
            appendLine("Things done: ${group("did_")}")
            appendLine("Setup: ${group("setup_")}")
            appendLine("Switches changed: ${group("switch_")}")
            appendLine("Links to the Mac: ${group("link_")}")
            appendLine("Problems shown: ${group(PROBLEM_PREFIX)}")
            appendLine("Switches now: $switches")
        }
    }

    /** An email to [FEEDBACK_TO] with room for the tester's words above the report. */
    fun feedbackEmail(context: Context): Intent {
        val body = "What happened, what you expected, anything confusing:\n\n\n\n" + report(context)
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_TO))
            putExtra(Intent.EXTRA_SUBJECT, "Lync feedback (${BuildConfig.VERSION_NAME}, build ${BuildConfig.VERSION_CODE})")
            putExtra(Intent.EXTRA_TEXT, body)
        }
    }
}
