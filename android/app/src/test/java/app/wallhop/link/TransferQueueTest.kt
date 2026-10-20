package app.wallhop.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferQueueTest {
    private fun t(id: String, phase: TransferPhase) = TransferState(id, emptyList(), 0, 0, phase)

    @Test fun firstInFirstOut() {
        var list = emptyList<TransferState>()
        list = TransferQueue.enqueue(list, t("a", TransferPhase.Waiting))
        list = TransferQueue.enqueue(list, t("b", TransferPhase.Waiting))
        assertEquals("a", TransferQueue.next(list)?.id)
        list = list.map { if (it.id == "a") it.copy(phase = TransferPhase.Sending) else it }
        // Nothing else starts while one is sending, and a new share never replaces it.
        assertNull(TransferQueue.next(list))
        list = TransferQueue.enqueue(list, t("c", TransferPhase.Waiting))
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
        assertEquals("a", TransferQueue.shown(list)?.id)
        assertEquals(2, TransferQueue.waitingBehind(list))
        list = list.map { if (it.id == "a") it.copy(phase = TransferPhase.Done) else it }
        assertEquals("b", TransferQueue.next(list)?.id)
        assertEquals("b", TransferQueue.shown(list)?.id)
    }

    @Test fun shownFallsBackToLatestFinished() {
        val list = listOf(t("a", TransferPhase.Done), t("b", TransferPhase.Failed))
        assertEquals("b", TransferQueue.shown(list)?.id)
        assertNull(TransferQueue.next(list))
        assertNull(TransferQueue.shown(emptyList()))
    }

    @Test fun oldFinishedOnesAreTrimmed() {
        var list = (1..5).map { t("done$it", TransferPhase.Done) }
        list = TransferQueue.enqueue(list, t("new", TransferPhase.Waiting))
        assertEquals(listOf("done3", "done4", "done5", "new"), list.map { it.id })
    }

    @Test fun pendingCodesKeepTheirTime() {
        val codes = PendingCodes(maxAgeMs = 5 * 60_000L)
        codes.add(PendingCodes.Code("111111", "Bank", "SMS", time = 0))
        codes.add(PendingCodes.Code("222222", "Shop", "SMS", time = 60_000))
        codes.add(PendingCodes.Code("222222", "Shop", "SMS", time = 70_000)) // Same code again: one entry.
        // At 5 min 30 s the first is too old; the second is still good.
        val fresh = codes.drain(330_000)
        assertEquals(listOf("222222"), fresh.map { it.code })
        assertEquals(70_000L, fresh.single().time)
        assertEquals(0, codes.size())
    }
}
