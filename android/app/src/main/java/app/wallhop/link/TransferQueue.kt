package app.wallhop.link

/**
 * Pure rules for the send queue (files and shared text): first in, first out, one at a time.
 * A new share never cancels one that's waiting or sending; it waits its turn.
 */
object TransferQueue {
    fun active(state: TransferState) = state.phase == TransferPhase.Waiting || state.phase == TransferPhase.Sending

    /** The next one to run: the oldest waiting, but only when nothing is sending. */
    fun next(list: List<TransferState>): TransferState? =
        if (list.any { it.phase == TransferPhase.Sending }) null else list.firstOrNull { it.phase == TransferPhase.Waiting }

    /**
     * What Home's transfer card shows: the one sending, else the oldest waiting, else the most
     * recently finished.
     */
    fun shown(list: List<TransferState>): TransferState? =
        list.firstOrNull { it.phase == TransferPhase.Sending }
            ?: list.firstOrNull { it.phase == TransferPhase.Waiting }
            ?: list.lastOrNull()

    /** How many are still to go behind [shown] (for "2 more waiting"). */
    fun waitingBehind(list: List<TransferState>): Int = (list.count(::active) - 1).coerceAtLeast(0)

    /** Adds [state] at the back, keeping finished ones only while they're the latest few. */
    fun enqueue(list: List<TransferState>, state: TransferState, keepFinished: Int = 3): List<TransferState> {
        val finished = list.filterNot(::active).takeLast(keepFinished)
        return list.filter { it.id != state.id && (active(it) || it in finished) } + state
    }
}

/**
 * Codes that couldn't reach the Mac (it was away for more than 20 s). Kept with their time and
 * sent on reconnect while under five minutes old; older ones are dropped, a code that late is no use.
 */
class PendingCodes(private val maxAgeMs: Long = MAX_AGE_MS) {
    companion object {
        /** A code older than this is no use any more. */
        const val MAX_AGE_MS = 5 * 60_000L
    }

    data class Code(val code: String, val sender: String, val app: String, val time: Long)

    private val codes = ArrayList<Code>()

    @Synchronized
    fun add(code: Code) {
        codes.removeAll { it.code == code.code }
        codes += code
    }

    /** The codes still fresh at [now], oldest first; empties the list. */
    @Synchronized
    fun drain(now: Long): List<Code> {
        val fresh = codes.filter { now - it.time < maxAgeMs }
        codes.clear()
        return fresh
    }

    @Synchronized
    fun size() = codes.size
}
