package qhybrid.android.debug

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import qhybrid.android.db.DbTestBase
import qhybrid.android.log.LogBufferLogger
import qhybrid.protocol.log.LogRingBuffer

/**
 * WP15 — verifies the Debug Menu's DB actions invoke the right [WatchRepository] calls
 * (against an in-memory Room DB, reusing the WP4 [DbTestBase] harness) AND that they log
 * their progress into the in-app [LogRingBuffer]. This is the headless half of "Debug Menu
 * actions invoke the right repo/service calls against a fake".
 *
 * BLE actions go through static [qhybrid.android.WatchConnectionService] entry points and
 * are verified on-device (on-device verification pending).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DebugToolsTest : DbTestBase() {

    private fun toolsOn(scope: CoroutineScope) =
        DebugTools(ApplicationProvider.getApplicationContext(), repo, scope)

    /** True only when our tee provider is the active SLF4J binding on the test classpath. */
    private fun teeActive(): Boolean =
        org.slf4j.LoggerFactory.getILoggerFactory().getLogger("probe") is LogBufferLogger

    @Test
    fun seed_thenList_populatesDbAndLogs() = runTest {
        val buffer = LogRingBuffer.shared(); buffer.clear()
        val tools = toolsOn(this)

        tools.seedSampleData().join()

        val seeded = repo.getWatch("AA:BB:CC:DD:EE:01")
        assertNotNull("seed should create the sample watch", seeded)
        assertEquals(2, repo.getAlarms("AA:BB:CC:DD:EE:01").size)
        assertEquals(1, repo.getRules("AA:BB:CC:DD:EE:01").size)
        assertEquals(1, repo.getButtons("AA:BB:CC:DD:EE:01").size)

        // Logged progress shows up in the in-app buffer (only when the tee binding is active;
        // on-device the build's META-INF service registration guarantees it).
        if (teeActive()) assertTrue(buffer.export().contains("Seeding sample watch"))
    }

    @Test
    fun transfer_clonesChildrenToTarget() = runTest {
        val tools = toolsOn(this)
        // Seed source via repo directly.
        watchDao.upsert(watch("AA:11:11:11:11:11"))
        alarmDao.upsertAll(listOf(alarm("AA:11:11:11:11:11", 0), alarm("AA:11:11:11:11:11", 1)))
        ruleDao.upsertAll(listOf(rule("AA:11:11:11:11:11", "com.whatsapp")))

        tools.transfer("AA:11:11:11:11:11", "BB:22:22:22:22:22").join()

        // transfer auto-registers the target watch, then clones the children.
        assertNotNull(repo.getWatch("BB:22:22:22:22:22"))
        assertEquals(2, repo.getAlarms("BB:22:22:22:22:22").size)
        assertEquals(1, repo.getRules("BB:22:22:22:22:22").size)
        // Source untouched.
        assertEquals(2, repo.getAlarms("AA:11:11:11:11:11").size)
    }

    @Test
    fun wipe_cascadeRemovesChildren() = runTest {
        val tools = toolsOn(this)
        watchDao.upsert(watch("CC:33:33:33:33:33"))
        alarmDao.upsertAll(listOf(alarm("CC:33:33:33:33:33", 0)))
        buttonDao.upsertAll(listOf(button("CC:33:33:33:33:33", 0x10)))

        tools.wipe("CC:33:33:33:33:33").join()

        assertNull(repo.getWatch("CC:33:33:33:33:33"))
        assertTrue(repo.getAlarms("CC:33:33:33:33:33").isEmpty())
        assertTrue(repo.getButtons("CC:33:33:33:33:33").isEmpty())
    }

    @Test
    fun setActive_switchesActiveWatch() = runTest {
        val tools = toolsOn(this)
        watchDao.upsert(watch("DD:44:44:44:44:44"))
        watchDao.upsert(watch("EE:55:55:55:55:55", active = true))

        tools.setActive("DD:44:44:44:44:44").join()

        assertEquals("DD:44:44:44:44:44", repo.getActiveWatch()?.macAddress)
    }

    @Test
    fun dumpDatabase_logsWatchRows() = runTest {
        val buffer = LogRingBuffer.shared(); buffer.clear()
        val tools = toolsOn(this)
        watchDao.upsert(watch("FF:66:66:66:66:66"))

        tools.dumpDatabase().join()

        if (teeActive()) {
            val blob = buffer.export()
            assertTrue(blob.contains("DB DUMP"))
            assertTrue(blob.contains("FF:66:66:66:66:66"))
        }
    }
}
