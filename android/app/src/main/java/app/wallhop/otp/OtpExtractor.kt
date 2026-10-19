package app.wallhop.otp

import kotlin.math.abs

/**
 * Finds a one-time code in an SMS or notification.
 *
 * A message only counts if it mentions a code-related word (in many languages). Among the
 * number-like tokens, amounts, card or account endings, phone numbers and dates are skipped,
 * and the token closest to the code word wins, with a preference for 6 digits.
 */
object OtpExtractor {

    private val keywords = listOf(
        // English
        "otp", "one time password", "one-time password", "one time pin", "verification", "verify",
        "code", "passcode", "pin", "2fa", "two-factor", "login", "log in", "sign in", "sign-in",
        "security", "authentication", "authenticate", "auth", "confirm", "token",
        // Other languages
        "código", "codigo", "clave", "contraseña", "senha", "verificação", "verificación",
        "code de", "vérification", "bestätigungscode", "sicherheitscode", "kode", "verifikasi",
        "mã", "xác", "codice", "verifica", "код", "пароль", "kodu", "doğrulama", "رمز", "کد",
        "ओटीपी", "सत्यापन", "कोड", "ওটিপি", "கடவுச்", "验证码", "驗證碼", "校验码", "動態密碼",
        "認証", "確認コード", "인증", "รหัส",
    )

    private val candidatePattern = Regex(
        // 4–8 digits, optionally split once by a space or dash (123 456, 123-456),
        // optionally with a short letter prefix like "G-".
        // Only Latin letters and digits block a match, so "验证码664120" still works.
        """(?<![A-Za-z0-9])(?:[A-Z]{1,3}-)?(\d{3,4}[ -]\d{3,4}|\d{4,8})(?![\p{N}])"""
    )

    private val alphanumericPattern = Regex("""(?<![\p{L}\p{N}])([A-Z0-9]{6,8})(?![\p{L}\p{N}])""")

    // A letter just before ("Roblox: ", "Dropbox ") means it's the end of a word, not "x"/"box".
    private val amountBefore = Regex("""(?<![\p{L}])(?:rs\.?|inr|₹|\$|usd|eur|€|£|gbp|amt|amount|bal|balance)\s*:?\s*$""", RegexOption.IGNORE_CASE)
    private val accountBefore = Regex("""(?<![\p{L}])(?:a/c|acct|account|card|ending|ending in|xx+|\*+|x+|no\.?|number|ref|ref\.? no|order|txn|id)\s*:?\s*$""", RegexOption.IGNORE_CASE)
    /**
     * Grouped numbers like 1-888-280-4331 or +91 98765 43210: three or more groups are a phone
     * number, never a code (codes split at most once, as in 123-456).
     */
    private val phoneNumber = Regex("""(?<![\p{N}])(?:\+\d{1,3}[ -]?)?(?:\(\d{2,4}\)\s?|\d{1,5}[ -])\d{2,5}[ -]\d{3,5}(?:[ -]\d{2,5})*(?![\p{N}])""")
    private val phoneBefore = Regex("""(?<![\p{L}])(?:call|tel|phone|helpline|dial|contact us|contact)\.?\s*(?:us\s*)?(?:on|at)?\s*:?\s*\+?$""", RegexOption.IGNORE_CASE)
    private val decimalAfter = Regex("""^[.,]\d""")
    private val strongBefore = Regex("""(?:otp|code|pin|passcode|password|is|:|：)\s*(?:is)?\s*:?\s*$""", RegexOption.IGNORE_CASE)
    private val strongAfter = Regex("""^\s*(?:is your|is the|is ur|as your|為|为|は|은|는|là)""", RegexOption.IGNORE_CASE)

    fun extract(text: String): String? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        val keywordPositions = keywords.flatMap { word -> lower.indicesOf(word) }
        if (keywordPositions.isEmpty()) return null

        data class Candidate(val code: String, val start: Int, val end: Int, val score: Int)

        val phoneRanges = phoneNumber.findAll(text).map { it.range }.toList()
        val candidates = mutableListOf<Candidate>()
        for (match in candidatePattern.findAll(text)) {
            val group = match.groups[1] ?: continue
            val code = group.value.replace(" ", "").replace("-", "")
            if (code.length !in 4..8) continue
            val start = match.range.first
            val end = match.range.last + 1
            val before = text.substring(maxOf(0, start - 14), start)
            val after = text.substring(end, minOf(text.length, end + 12))

            if (amountBefore.containsMatchIn(before) || decimalAfter.containsMatchIn(after)) continue
            if (accountBefore.containsMatchIn(before)) continue
            if (phoneRanges.any { start <= it.last && end > it.first } || phoneBefore.containsMatchIn(before)) continue
            if (code.length == 4 && code.toInt() in 1900..2099 && !strongBefore.containsMatchIn(before)) continue

            val distance = keywordPositions.minOf { position ->
                if (position < start) start - position else abs(position - end)
            }
            var score = 100 - minOf(distance, 100)
            if (code.length == 6) score += 12
            if (code.length in 5..8) score += 4
            if (strongBefore.containsMatchIn(before)) score += 20
            if (strongAfter.containsMatchIn(after)) score += 20
            candidates += Candidate(code, start, end, score)
        }

        candidates.maxByOrNull { it.score }?.let { return it.code }

        // Some services use letters and digits, for example "Your code is A7K29Q".
        return alphanumericPattern.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { token -> token.any(Char::isDigit) && token.any(Char::isLetter) && token.count(Char::isDigit) >= 2 }
            ?.takeIf { token ->
                val index = text.indexOf(token)
                keywordPositions.any { abs(it - index) < 40 }
            }
    }

    private fun String.indicesOf(word: String): List<Int> {
        val result = mutableListOf<Int>()
        var index = indexOf(word)
        while (index >= 0) {
            // Latin keywords must start at a word boundary ("pin" shouldn't match "shopping").
            val boundary = index == 0 || !this[index - 1].isLetter() || !word[0].isLatinLetter()
            if (boundary) result += index
            index = indexOf(word, index + 1)
        }
        return result
    }

    private fun Char.isLatinLetter() = this in 'a'..'z'
}
