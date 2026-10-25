package app.wallhop.core

import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/** Something that isn't a Lync frame or message arrived: another service on that port, or a different protocol. */
class BadFrameException(message: String) : IOException(message)

/** Length-prefixed frames: 4-byte big-endian length, then the payload. See docs/PROTOCOL.md. */
object Framing {
    const val MAX_PAYLOAD = 1 shl 20

    fun write(output: OutputStream, payload: ByteArray) {
        val header = ByteBuffer.allocate(4).putInt(payload.size).array()
        output.write(header)
        output.write(payload)
    }

    fun read(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length < 0 || length > MAX_PAYLOAD) throw BadFrameException("Frame too large: $length")
        val payload = ByteArray(length)
        input.readFully(payload)
        return payload
    }
}

/** A message inside a sealed frame: a small JSON header plus an optional binary body. */
class Message(val header: JSONObject, val body: ByteArray = ByteArray(0)) {

    val type: String get() = header.optString("t")

    fun string(key: String): String? = if (header.has(key) && !header.isNull(key)) header.optString(key) else null

    val bodyText: String get() = String(body, Charsets.UTF_8)

    fun encode(): ByteArray {
        val json = header.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= 0xFFFF) { "Header too large" }
        return ByteBuffer.allocate(2 + json.size + body.size)
            .putShort(json.size.toShort())
            .put(json)
            .put(body)
            .array()
    }

    companion object {
        fun of(type: String, vararg fields: Pair<String, Any?>, body: ByteArray = ByteArray(0)): Message {
            val header = JSONObject().put("t", type)
            fields.forEach { (key, value) -> if (value != null) header.put(key, value) }
            return Message(header, body)
        }

        fun decode(data: ByteArray): Message {
            if (data.size < 2) throw BadFrameException("Malformed message")
            val length = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            if (data.size < 2 + length) throw BadFrameException("Malformed message")
            val header = JSONObject(String(data, 2, length, Charsets.UTF_8))
            return Message(header, data.copyOfRange(2 + length, data.size))
        }
    }
}
