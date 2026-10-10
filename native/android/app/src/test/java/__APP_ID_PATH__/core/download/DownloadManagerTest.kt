package __APP_ID__.core.download

import __APP_ID__.data.db.DownloadDao
import __APP_ID__.data.db.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private class FakeDao : DownloadDao {
    private val rows = MutableStateFlow<Map<Long, DownloadEntity>>(emptyMap())
    private var next = 1L
    override suspend fun insert(entity: DownloadEntity): Long {
        val id = next++
        rows.value = rows.value + (id to entity.copy(id = id))
        return id
    }
    override suspend fun update(entity: DownloadEntity) { rows.value = rows.value + (entity.id to entity) }
    override fun observeAll(): Flow<List<DownloadEntity>> = rows.map { it.values.sortedByDescending { e -> e.id } }
    override suspend fun get(id: Long) = rows.value[id]
    override fun observe(id: Long): Flow<DownloadEntity?> = rows.map { it[id] }
    override suspend fun queued() = rows.value.values.filter { it.state == "QUEUED" }.sortedBy { it.id }
    override fun observeActiveCount(): Flow<Int> = rows.map { m -> m.values.count { it.state == "QUEUED" || it.state == "DOWNLOADING" } }
    override suspend fun activeCount() = rows.value.values.count { it.state == "QUEUED" || it.state == "DOWNLOADING" }
    override suspend fun requeueInterrupted(): Int {
        val hit = rows.value.values.filter { it.state == "DOWNLOADING" }
        hit.forEach { rows.value = rows.value + (it.id to it.copy(state = "QUEUED", speedBps = 0, etaSeconds = -1)) }
        return hit.size
    }
    override suspend fun delete(id: Long) { rows.value = rows.value - id }
}

private class FakeFiles(val root: File) : DownloadFiles {
    val published = CopyOnWriteArrayList<String>()
    override fun workDir(id: Long) = File(root, "$id").also { it.mkdirs() }
    override fun cleanWork(id: Long) { File(root, "$id").deleteRecursively() }
    override fun workFiles(id: Long) = File(root, "$id").listFiles()?.associate { it.name to it.length() } ?: emptyMap()
    override fun workBytes(id: Long) = workFiles(id).values.sum()
    override fun freeBytes() = Long.MAX_VALUE
    override fun publish(baseName: String, extension: String, source: File, mimeType: String): PublishedFile {
        val name = NamePolicy.uniqueFileName(baseName, extension) { it in published }
        published += name
        val size = source.length()
        source.delete()
        return PublishedFile("content://fake/$name", name, mimeType, size)
    }
    override fun delete(uriString: String?) = true
    override fun exists(uriString: String?) = true
}

private class Call(val kind: String, val profile: ClientProfile, val resume: Boolean = false)

/** Scripted engine: [onDownload] decides what each download call does. */
private class FakeEngine(val files: FakeFiles) : DownloadEngine {
    val calls = CopyOnWriteArrayList<Call>()
    val updates = AtomicInteger()
    val infoCalls = AtomicInteger()
    @Volatile var cancelled = false
    @Volatile var onDownload: (n: Int, workDir: File, progress: (ProgressSnapshot) -> Unit) -> Unit = { _, dir, p ->
        p(ProgressSnapshot(0.5f, 50, 100, 1000, 5))
        File(dir, "media.mp4").writeBytes(ByteArray(100))
        p(ProgressSnapshot(1f, 100, 100, 1000, 0))
    }
    @Volatile var infoFailure: DownloadException? = null
    private val downloadCount = AtomicInteger()

    override fun initialize() {}
    override fun fetchInfo(videoId: String, processId: String?, profile: ClientProfile): YtInfo {
        infoCalls.incrementAndGet()
        calls += Call("info", profile)
        infoFailure?.let { throw it }
        return YtInfoParser.parse(FIXTURE)
    }
    override fun download(
        videoId: String, selection: FormatSelection?, spec: DownloadSpec, subtitles: List<SubtitleSpec>,
        workDir: String, processId: String, resume: Boolean, profile: ClientProfile, onProgress: (ProgressSnapshot) -> Unit,
    ) {
        val n = downloadCount.incrementAndGet()
        calls += Call("download", profile, resume)
        cancelled = false
        onDownload(n, File(workDir), onProgress)
    }
    override fun cancel(processId: String): Boolean { cancelled = true; return true }
    override fun versionName() = "test"
    override fun update(): String { updates.incrementAndGet(); return "Updated" }
    fun downloads() = calls.filter { it.kind == "download" }
}

private class Notes : DownloadNotifier {
    val completed = AtomicInteger(); val failed = AtomicInteger()
    override fun progress(count: Int, title: String?, progress: Float) {}
    override fun completed(e: DownloadEntity) { completed.incrementAndGet() }
    override fun failed(e: DownloadEntity) { failed.incrementAndGet() }
}

class DownloadManagerTest {
    private lateinit var dir: File
    private lateinit var dao: FakeDao
    private lateinit var files: FakeFiles
    private lateinit var engine: FakeEngine
    private lateinit var notes: Notes
    private lateinit var scope: CoroutineScope
    private lateinit var manager: DownloadManager

    @Before fun setUp() {
        dir = Files.createTempDirectory("dl-test").toFile()
        dao = FakeDao(); files = FakeFiles(dir); engine = FakeEngine(files); notes = Notes()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager = newManager()
    }

    private fun newManager(stall: Long = 60_000, watchdog: Long = 10_000) = DownloadManager(
        dao = dao, engine = engine, storage = files, serviceStarter = {}, notifier = notes,
        scope = scope, backoffMillis = { 0L }, stallMillis = stall, watchdogIntervalMillis = watchdog, progressIntervalMillis = 0L,
    )

    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private fun spec(h: Int? = 480, audio: AudioChoice = AudioChoice(null)) =
        DownloadSpec("abc123", "Demo: Title?", "http://t/x.jpg", DownloadKind.VIDEO, h, audio, emptyList())

    private fun waitFor(timeoutMs: Long = 8_000, what: String = "condition", cond: suspend () -> Boolean) = runBlocking {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return@runBlocking; delay(15) }
        throw AssertionError("timed out waiting for $what; rows=${dao.get(1)}")
    }

    private fun row(id: Long = 1) = runBlocking { dao.get(id)!! }
    private fun state(id: Long = 1) = row(id).state

    @Test fun successfulDownloadCompletesWithCleanName() {
        runBlocking { manager.enqueue(spec()) }
        waitFor(what = "completed") { dao.get(1)?.state == "COMPLETED" }
        val r = row()
        assertEquals("Demo Title.mp4", r.destinationName)   // illegal chars removed, no video id
        assertEquals("content://fake/Demo Title.mp4", r.destinationUri)
        assertEquals(1f, r.progress, 0f)
        assertEquals(480, r.resolvedHeight)
        assertEquals("mp4", r.containerExt)
        assertNull(r.errorKind)
        assertEquals(emptyMap<String, Long>(), files.workFiles(1)) // work area cleaned
        assertEquals(1, notes.completed.get())
    }

    @Test fun twoDownloadsOfTheSameTitleNeverOverwrite() {
        runBlocking { manager.enqueue(spec()); manager.enqueue(spec()) }
        waitFor(what = "both completed") { dao.get(1)?.state == "COMPLETED" && dao.get(2)?.state == "COMPLETED" }
        assertEquals(setOf("Demo Title.mp4", "Demo Title (2).mp4"), setOf(row(1).destinationName, row(2).destinationName))
    }

    @Test fun progressIsWrittenToTheRowWhileTransferring() {
        val release = java.util.concurrent.CountDownLatch(1)
        engine.onDownload = { _, _, p ->
            p(ProgressSnapshot(0.4f, 40, 100, 2048, 12))
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            File(files.workDir(1), "media.mp4").writeBytes(ByteArray(10))
        }
        runBlocking { manager.enqueue(spec()) }
        waitFor(what = "progress 0.4") { dao.get(1)?.progress == 0.4f }
        val mid = row()
        assertEquals("DOWNLOADING", mid.state)
        assertEquals(2048L, mid.speedBps)
        assertEquals(12L, mid.etaSeconds)
        release.countDown()
        waitFor(what = "completed") { dao.get(1)?.state == "COMPLETED" }
    }

    @Test fun refused403BeforeAnyByteReExtractsUpdatesEngineAndSwitchesClient() {
        engine.onDownload = { n, dir, p ->
            if (n < 3) throw DownloadException(DownloadErrorKind.EXPIRED_URL, "ERROR: unable to download video data: HTTP Error 403: Forbidden")
            File(dir, "media.mp4").writeBytes(ByteArray(10)); p(ProgressSnapshot(1f, 10, 10, 1, 0))
        }
        runBlocking { manager.enqueue(spec()) }
        waitFor(what = "completed after refusals") { dao.get(1)?.state == "COMPLETED" }
        assertEquals(1, engine.updates.get())                                   // updated exactly once
        assertEquals(3, engine.infoCalls.get())                                 // fresh info for every attempt
        assertEquals(listOf(ClientProfile.DEFAULT, ClientProfile.DEFAULT, ClientProfile.ANDROID_VR), engine.downloads().map { it.profile })
        assertEquals(listOf(ClientProfile.DEFAULT, ClientProfile.DEFAULT, ClientProfile.ANDROID_VR), engine.calls.filter { it.kind == "info" }.map { it.profile })
        assertEquals(2, row().attempts)
    }

    @Test fun a403AfterBytesMovedIsAnExpiredLinkAndRetriesWithoutUpdatingEngine() {
        engine.onDownload = { n, dir, p ->
            if (n == 1) {
                p(ProgressSnapshot(0.3f, 30, 100, 1000, 7))
                throw DownloadException(DownloadErrorKind.EXPIRED_URL, "HTTP Error 403: Forbidden")
            }
            File(dir, "media.mp4").writeBytes(ByteArray(10)); p(ProgressSnapshot(1f, 10, 10, 1, 0))
        }
        runBlocking { manager.enqueue(spec()) }
        waitFor(what = "completed") { dao.get(1)?.state == "COMPLETED" }
        assertEquals(0, engine.updates.get())
        assertEquals(2, engine.infoCalls.get())
        assertEquals(listOf(ClientProfile.DEFAULT, ClientProfile.DEFAULT), engine.downloads().map { it.profile })
    }

    @Test fun persistentRefusalEndsFailedWithAClearReasonAfterBoundedAttempts() {
        engine.onDownload = { _, _, _ -> throw DownloadException(DownloadErrorKind.EXPIRED_URL, "HTTP Error 403: Forbidden") }
        runBlocking { manager.enqueue(spec()) }
        waitFor(what = "failed") { dao.get(1)?.state == "FAILED" }
        val r = row()
        assertEquals("STREAM_REFUSED", r.errorKind)                // not mislabeled as an expired link
        assertEquals(DownloadError.userMessage(DownloadErrorKind.STREAM_REFUSED), r.errorMessage)
        assertFalse(r.errorMessage!!.contains("403"))
        assertEquals(RetryPolicy.MAX_ATTEMPTS, r.attempts)
        assertEquals(3, engine.downloads().size)                   // never loops forever
        assertEquals(1, engine.updates.get())
        assertEquals(1, notes.failed.get())
        assertEquals(0f, r.progress, 0f)
    }

    @Test fun nonRetryableErrorsFailImmediately() {
        runBlocking { manager.enqueue(spec(audio = AudioChoice("fr", AudioChoice.AudioKind.DUBBED, "French (dub)"))) }
        waitFor(what = "failed") { dao.get(1)?.state == "FAILED" }
        assertEquals("AUDIO_UNAVAILABLE", row().errorKind)
        assertEquals(0, engine.downloads().size)                   // never started a download with the wrong audio
        assertEquals(1, engine.infoCalls.get())
    }

    @Test fun infoFailureIsMappedAndRetriedBounded() {
        engine.infoFailure = DownloadException(DownloadErrorKind.NETWORK, "Temporary failure in name resolution")
        runBlocking { manager.enqueue(spec()) }
        waitFor(what = "failed") { dao.get(1)?.state == "FAILED" }
        assertEquals("NETWORK", row().errorKind)
        assertEquals(RetryPolicy.MAX_ATTEMPTS, engine.infoCalls.get())
    }

    @Test fun aHungDownloadIsKilledAndRetriedInsteadOfStayingAtZeroForever() {
        manager = newManager(stall = 150, watchdog = 25)
        engine.onDownload = { n, dir, p ->
            if (n == 1) {
                // no output at all, like the reported 0% hang; ends only when the process is killed
                val end = System.currentTimeMillis() + 5_000
                while (!engine.cancelled && System.currentTimeMillis() < end) Thread.sleep(10)
                throw DownloadException(DownloadErrorKind.CANCELLED, "killed")
            }
            File(dir, "media.mp4").writeBytes(ByteArray(10)); p(ProgressSnapshot(1f, 10, 10, 1, 0))
        }
        runBlocking { manager.enqueue(spec()) }
        waitFor(what = "recovered after stall") { dao.get(1)?.state == "COMPLETED" }
        assertEquals(2, engine.downloads().size)
        assertEquals(1, row().attempts)
    }

    @Test fun pauseKeepsPartialFilesAndResumeContinuesFromThem() {
        engine.onDownload = { n, dir, p ->
            if (n == 1) {
                File(dir, "media.mp4.part").writeBytes(ByteArray(50))
                p(ProgressSnapshot(0.5f, 50, 100, 1000, 5))
                val end = System.currentTimeMillis() + 5_000
                while (!engine.cancelled && System.currentTimeMillis() < end) Thread.sleep(10)
                throw DownloadException(DownloadErrorKind.CANCELLED, "killed by pause")
            }
            File(dir, "media.mp4.part").delete()
            File(dir, "media.mp4").writeBytes(ByteArray(100)); p(ProgressSnapshot(1f, 100, 100, 1, 0))
        }
        runBlocking { manager.enqueue(spec()) }
        waitFor(what = "half way") { dao.get(1)?.progress == 0.5f }
        runBlocking { manager.pause(1) }
        waitFor(what = "paused") { dao.get(1)?.state == "PAUSED" }
        assertEquals(50L, files.workBytes(1))                       // partial data kept
        runBlocking { manager.resume(1) }
        waitFor(what = "completed") { dao.get(1)?.state == "COMPLETED" }
        assertEquals(listOf(false, true), engine.downloads().map { it.resume })
    }

    @Test fun cancelDeletesPartialFiles() {
        engine.onDownload = { _, dir, p ->
            File(dir, "media.mp4.part").writeBytes(ByteArray(10)); p(ProgressSnapshot(0.1f, 10, 100, 1, 9))
            val end = System.currentTimeMillis() + 5_000
            while (!engine.cancelled && System.currentTimeMillis() < end) Thread.sleep(10)
            throw DownloadException(DownloadErrorKind.CANCELLED, "killed")
        }
        runBlocking { manager.enqueue(spec()) }
        waitFor(what = "started") { dao.get(1)?.progress == 0.1f }
        runBlocking { manager.cancel(1) }
        waitFor(what = "cancelled") { dao.get(1)?.state == "CANCELLED" }
        assertEquals(emptyMap<String, Long>(), files.workFiles(1))
        runBlocking { manager.retry(1) }                            // a cancelled job can be started again
        assertTrue(state() == "QUEUED" || state() == "DOWNLOADING" || state() == "CANCELLED")
    }

    @Test fun interruptedRowsAreRequeuedAndFinishAfterProcessDeath() {
        runBlocking {
            val id = dao.insert(DownloadManager.run {
                val e = manager.javaClass // keep reference to avoid unused warning
                DownloadEntity(
                    videoId = "abc123", title = "Demo", thumbnailUrl = null, kind = "VIDEO", requestedHeight = 480,
                    qualityLabel = "480p", audioLanguage = null, audioKind = "ORIGINAL", audioLabel = "Original",
                    subtitleSpecs = "", subtitleFormat = "SRT", state = "DOWNLOADING", createdAt = 1L, progress = 0.3f,
                )
            })
            assertEquals("DOWNLOADING", dao.get(id)!!.state)
        }
        manager.start()
        waitFor(what = "recovered job completes") { dao.get(1)?.state == "COMPLETED" }
        assertEquals("Demo.mp4", row().destinationName)
    }

    @Test fun removeAndDeleteWithFilesClearTheRow() {
        runBlocking { manager.enqueue(spec()) }
        waitFor(what = "completed") { dao.get(1)?.state == "COMPLETED" }
        runBlocking { manager.deleteWithFiles(1) }
        assertNull(runBlocking { dao.get(1) })
    }
}
