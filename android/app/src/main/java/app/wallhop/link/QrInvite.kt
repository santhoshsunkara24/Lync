package app.wallhop.link

import android.net.Uri
import android.util.Base64

/**
 * What a Mac's pairing QR holds: `wallhop://pair?v=1&n=<name>&h=<ip,ip,…>&p=<port>&k=<mac key>&t=<secret>`
 * (PROTOCOL.md, QR pairing). Keys and secret are URL-safe base64 without padding.
 */
class QrInvite(val name: String, val hosts: List<String>, val port: Int, val macPub: ByteArray, val token: ByteArray) {
    val host: String get() = hosts.first()

    companion object {
        /** Null for anything that isn't a valid Wallhop pairing QR. */
        fun parse(text: String): QrInvite? = runCatching {
            val uri = Uri.parse(text.trim())
            if (uri.scheme != "wallhop" || uri.host != "pair" || uri.getQueryParameter("v") != "1") return null
            val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            QrInvite(
                name = uri.getQueryParameter("n")?.take(60) ?: "Mac",
                // All the Mac's addresses, comma-separated (Wi‑Fi, Ethernet, …).
                hosts = uri.getQueryParameter("h")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(8)
                    ?.takeIf { it.isNotEmpty() } ?: return null,
                port = uri.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null,
                macPub = Base64.decode(uri.getQueryParameter("k") ?: return null, flags).takeIf { it.size == 32 } ?: return null,
                token = Base64.decode(uri.getQueryParameter("t") ?: return null, flags).takeIf { it.size >= 16 } ?: return null,
            )
        }.getOrNull()
    }
}
