package __APP_ID__.core.download

import __APP_ID__.core.log.AppLog
import __APP_ID__.data.db.DownloadDao
import __APP_ID__.data.db.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What the foreground notification shows. */
data class ActiveInfo(val count: Int, val title: String?, val progress: Float, val indeterminate: Boolean) {
    companion object { val NONE = ActiveInfo(0, null, 0f, true) }
}

/**
 * Persistent download queue.
 *
 *  - Every job is a Room row; the manager is the only writer of progress and state.
 *  - At most [maxConcurrent] jobs run; the rest stay QUEUED.
 *  - Pause = the yt-dlp process is killed and its `.part` files are kept; Resume = a new process with
 *    `--continue` picks the files up. Cancel = kill + delete partial files. Nothing is simulated.
 *  - Each attempt re-extracts, so expired URLs are replaced by fresh ones. Failures are retried a
 *    bounded number of times ([RetryPolicy]); yt-dlp is updated once if extraction looks outdated.
 *  - On process death / reboot, rows left as DOWNLOADING are re-queued by [start] and resume.
 */
class DownloadManager(
    private val dao: DownloadDao,
    private val engine: DownloadEngine,
    private val storage: DownloadFiles,
    private val serviceStarter: () -> Unit,
    private val notifier: DownloadNotifier,
    private val maxConcurrent: Int = 2,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val backoffMillis: (Int) -> Long = RetryPolicy::backoffMillis,
    private val stallMillis: Long = STALL_MS,
    private val watchdogIntervalMillis: Long = 10_000L,
    private val progressIntervalMillis: Long = PROGRESS_INTERVAL_MS,
) {
    private enum class Intent { NONE, PAUSE, CANCEL, REMOVE }

    private class Running(val processId: String) {
        @Volatile var intent = Intent.NONE
        @Volatile var title: String? = null
        @Volatile var progress = 0f
        /** True once real bytes moved in the current attempt (a 403 after that means an expired link). */
        @Volatile var transferStarted = false
        /** Set by the watchdog when nothing happened for too long and the process was killed. */
        @Volatile var stalled = false
        @Volatile var lastActivity = System.currentTimeMillis()
        var job: Job? = null
    }

    private val running = ConcurrentHashMap<Long, Running>()
    private val pumpMutex = Mutex()
    private val _active = MutableStateFlow(ActiveInfo.NONE)
    val active: StateFlow<ActiveInfo> = _active.asStateFlow()
    @Volatile private var engineUpdatedThisSession = false

    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()
    fun observeAllNullable(): Flow<List<DownloadEntity>?> = dao.observeAll()
    suspend fun get(id: Long): DownloadEntity? = dao.get(id)
    /** Both may unpack the bundled runtime on first use, so they run off the main thread. */
    suspend fun engineVersion(): String = withContext(Dispatchers.IO) {
        try { engine.initialize(); engine.versionName() } catch (e: DownloadException) { "unavailable" }
    }

    @Throws(DownloadException::class)
    suspend fun updateEngine(): String = withContext(Dispatchers.IO) { engine.initialize(); engine.update() }

    // ───────────── lifecycle ─────────────

    /** Call once at app start (and from the service): recover interrupted jobs and run the queue. */
    fun start() {
        scope.launch {
            val n = dao.requeueInterrupted()
            if (n > 0) AppLog.i("Download", "recovered $n interrupted download(s)")
            pump()
        }
    }

    suspend fun enqueue(spec: DownloadSpec): Long {
        val id = dao.insert(
            DownloadEntity(
                videoId = spec.videoId,
                title = spec.title,
                thumbnailUrl = spec.thumbnailUrl,
                kind = spec.kind.name,
                requestedHeight = spec.height,
                qualityLabel = spec.qualityLabel,
                audioLanguage = spec.audio.language,
                audioKind = spec.audio.kind.name,
                audioLabel = spec.audio.label,
                subtitleSpecs = SubtitleSpec.encode(spec.subtitles),
                subtitleFormat = spec.subtitleFormat.name,
                state = DownloadState.QUEUED.name,
                createdAt = System.currentTimeMillis(),
            ),
        )
        AppLog.i("Download", "queued #$id ${spec.qualityLabel} audio=${spec.audio.label} subs=${spec.subtitles.size}")
        pump()
        return id
    }

    // ───────────── user actions ─────────────

    suspend fun pause(id: Long) {
        val r = running[id]
        if (r != null) {
            r.intent = Intent.PAUSE
            engine.cancel(r.processId)
        } else mutate(id) { if (DownloadState.valueOf(it.state).isActive) it.copy(state = DownloadState.PAUSED.name, speedBps = 0, etaSeconds = -1) else it }
    }

    suspend fun resume(id: Long) {
        mutate(id) { if (it.state == DownloadState.PAUSED.name) it.copy(state = DownloadState.QUEUED.name) else it }
        pump()
    }

    /** From FAILED or CANCELLED: start over (partial files of a cancelled job were already deleted). */
    suspend fun retry(id: Long) {
        mutate(id) {
            if (it.state == DownloadState.FAILED.name || it.state == DownloadState.CANCELLED.name)
                it.copy(state = DownloadState.QUEUED.name, attempts = 0, errorKind = null, errorMessage = null, progress = 0f, speedBps = 0, etaSeconds = -1)
            else it
        }
        pump()
    }

    suspend fun cancel(id: Long) {
        val r = running[id]
        if (r != null) {
            r.intent = Intent.CANCEL
            engine.cancel(r.processId)
        } else {
            storage.cleanWork(id)
            mutate(id) { if (DownloadState.valueOf(it.state).isFinished) it else it.copy(state = DownloadState.CANCELLED.name, speedBps = 0, etaSeconds = -1, progress = 0f) }
        }
    }

    /** Removes the row (and partial files). Finished media files are kept. */
    suspend fun remove(id: Long) {
        val r = running[id]
        if (r != null) {
            r.intent = Intent.REMOVE
            engine.cancel(r.processId)
        } else {
            storage.cleanWork(id)
            dao.delete(id)
        }
    }

    /** Removes the row AND the downloaded files. */
    suspend fun deleteWithFiles(id: Long) {
        val e = dao.get(id) ?: return
        storage.delete(e.destinationUri)
        subtitleFileUris(e).forEach { storage.delete(it.second) }
        remove(id)
    }

    fun fileExists(e: DownloadEntity): Boolean = storage.exists(e.destinationUri)

    // ───────────── queue ─────────────

    private suspend fun pump() = pumpMutex.withLock {
        val free = maxConcurrent - running.size
        if (free <= 0) return@withLock
        for (e in dao.queued().filter { !running.containsKey(it.id) }.take(free)) launchJob(e)
    }

    private fun launchJob(e: DownloadEntity) {
        val r = Running(processId = "dl-${e.id}")
        r.title = e.title
        running[e.id] = r
        publishActive()
        serviceStarter()
        r.job = scope.launch {
            try {
                runJob(e.id, r)
            } catch (t: Throwable) {
                AppLog.e("Download", "job #${e.id} crashed", t)
                runCatching { mutate(e.id) { it.copy(state = DownloadState.FAILED.name, errorKind = DownloadErrorKind.UNKNOWN.name, errorMessage = DownloadError.userMessage(DownloadErrorKind.UNKNOWN)) } }
            } finally {
                running.remove(e.id)
                publishActive()
                pump()
            }
        }
    }

    private fun publishActive(snapshot: ProgressSnapshot? = null) {
        val list = running.values.toList()
        if (list.isEmpty()) { _active.value = ActiveInfo.NONE; return }
        val first = list.first()
        if (snapshot != null) first.progress = snapshot.progress
        _active.value = ActiveInfo(list.size, first.title, first.progress, indeterminate = first.progress <= 0f)
    }

    // ───────────── one job ─────────────

    private suspend fun runJob(id: Long, r: Running) {
        var entity = dao.get(id) ?: return
        if (r.intent != Intent.NONE) return finishIntent(id, r)
        val spec = toSpec(entity)
        entity = entity.copy(state = DownloadState.DOWNLOADING.name, errorKind = null, errorMessage = null, speedBps = 0, etaSeconds = -1)
        dao.update(entity)

        var attempt = entity.attempts
        var profile = ClientProfile.DEFAULT
        while (true) {
            val watchdog = scope.launch {
                while (true) {
                    delay(watchdogIntervalMillis)
                    if (r.intent == Intent.NONE && System.currentTimeMillis() - r.lastActivity > stallMillis) {
                        AppLog.w("Download", "#$id stalled for ${stallMillis / 1000}s without any progress; stopping the process")
                        r.stalled = true
                        engine.cancel(r.processId)
                        break
                    }
                }
            }
            try {
                r.transferStarted = false
                r.lastActivity = System.currentTimeMillis()
                entity = withContext(Dispatchers.IO) { attemptOnce(id, r, spec, entity, profile) }
                dao.update(entity)
                notifier.completed(entity)
                return
            } catch (e: DownloadException) {
                var kind = e.kind
                var technical = e.technical
                if (r.stalled && r.intent == Intent.NONE) {
                    r.stalled = false
                    kind = DownloadErrorKind.NETWORK
                    technical = "stalled: no progress for ${stallMillis / 1000}s"
                } else if (kind == DownloadErrorKind.CANCELLED || r.intent != Intent.NONE) {
                    return finishIntent(id, r)
                }
                kind = DownloadError.refine(kind, r.transferStarted)
                attempt++
                AppLog.w("Download", "#$id attempt $attempt failed: $kind client=${profile.name} transferStarted=${r.transferStarted} ${technical.lines().lastOrNull { it.isNotBlank() }}")
                when (RetryPolicy.next(kind, attempt, engineUpdatedThisSession)) {
                    RetryPolicy.Next.RETRY -> {
                        // A refused stream that survived an engine update: ask YouTube differently.
                        if (kind == DownloadErrorKind.STREAM_REFUSED) profile = profile.next()
                        entity = entity.copy(attempts = attempt)
                        dao.update(entity)
                        delay(backoffMillis(attempt))
                        if (r.intent != Intent.NONE) return finishIntent(id, r)
                    }
                    RetryPolicy.Next.UPDATE_ENGINE_THEN_RETRY -> {
                        engineUpdatedThisSession = true
                        AppLog.i("Download", "#$id $kind; updating yt-dlp once before retrying")
                        r.lastActivity = System.currentTimeMillis()
                        runCatching { withContext(Dispatchers.IO) { engine.update() } }
                            .onSuccess { AppLog.i("Download", "yt-dlp update: $it") }
                            .onFailure { AppLog.w("Download", "engine update failed: ${it.message}") }
                        entity = entity.copy(attempts = attempt)
                        dao.update(entity)
                    }
                    RetryPolicy.Next.FAIL -> {
                        // Keep partial files for FAILED so Retry can resume; they are removed on Remove/Cancel.
                        entity = entity.copy(
                            state = DownloadState.FAILED.name, attempts = attempt, speedBps = 0, etaSeconds = -1,
                            errorKind = kind.name, errorMessage = DownloadError.userMessage(kind),
                        )
                        dao.update(entity)
                        notifier.failed(entity)
                        return
                    }
                }
            } finally {
                watchdog.cancel()
            }
        }
    }

    private suspend fun finishIntent(id: Long, r: Running) {
        when (r.intent) {
            Intent.PAUSE -> mutate(id) { it.copy(state = DownloadState.PAUSED.name, speedBps = 0, etaSeconds = -1) }
            Intent.CANCEL -> {
                storage.cleanWork(id)
                mutate(id) { it.copy(state = DownloadState.CANCELLED.name, speedBps = 0, etaSeconds = -1, progress = 0f, downloadedBytes = -1) }
            }
            Intent.REMOVE -> {
                storage.cleanWork(id)
                dao.delete(id)
            }
            Intent.NONE -> mutate(id) { it.copy(state = DownloadState.FAILED.name, errorKind = DownloadErrorKind.UNKNOWN.name, errorMessage = DownloadError.userMessage(DownloadErrorKind.UNKNOWN)) }
        }
    }

    /** One full attempt: fresh info -> choose formats -> download -> publish. Blocking. */
    private fun attemptOnce(id: Long, r: Running, spec: DownloadSpec, start: DownloadEntity, profile: ClientProfile): DownloadEntity {
        engine.initialize()
        val info = engine.fetchInfo(spec.videoId, r.processId, profile)
        r.lastActivity = System.currentTimeMillis()
        if (r.intent != Intent.NONE) throw DownloadException(DownloadErrorKind.CANCELLED, "cancelled after info")

        val selection: FormatSelection? = if (spec.kind == DownloadKind.SUBTITLES_ONLY) null else try {
            FormatPicker.select(info, spec)
        } catch (e: SelectionException) {
            throw DownloadException(e.kind, e.message ?: "selection failed", e)
        }

        val subPlan = FormatPicker.planSubtitles(info, spec.subtitles)
        if (spec.kind == DownloadKind.SUBTITLES_ONLY && subPlan.available.isEmpty()) {
            throw DownloadException(DownloadErrorKind.SUBTITLE_UNAVAILABLE, "no requested subtitle exists: ${spec.subtitles}")
        }

        // Space for the work copy plus the final copy (+ margin).
        val estimate = selection?.estimatedBytes ?: -1L
        val have = storage.freeBytes()
        if (estimate > 0 && have < estimate * 2 + MARGIN_BYTES - storage.workBytes(id)) {
            throw DownloadException(DownloadErrorKind.STORAGE_FULL, "need ~${estimate * 2}, have $have")
        }

        val work = storage.workDir(id)
        val resume = storage.workBytes(id) > 0
        var last = 0L
        var cur = start.copy(
            resolvedHeight = selection?.resolvedHeight?.takeIf { it > 0 },
            containerExt = selection?.container,
            totalBytes = estimate,
        )
        engine.download(
            videoId = spec.videoId,
            selection = selection,
            spec = spec,
            subtitles = subPlan.available.map { it.first },
            workDir = work.absolutePath,
            processId = r.processId,
            resume = resume,
            profile = profile,
        ) { s ->
            r.lastActivity = System.currentTimeMillis()
            if (s.progress > 0f) r.transferStarted = true
            val now = System.currentTimeMillis()
            // Never let progress move backwards across a resume.
            val shown = maxOf(s.progress, start.progress.takeIf { resume } ?: 0f)
            r.progress = shown
            if (now - last >= progressIntervalMillis) {
                last = now
                cur = cur.copy(progress = shown, downloadedBytes = s.downloadedBytes, totalBytes = if (s.totalBytes > 0) s.totalBytes else cur.totalBytes, speedBps = s.speedBps, etaSeconds = s.etaSeconds)
                kotlinx.coroutines.runBlocking { dao.update(cur) }
                publishActive(s.copy(progress = shown))
                notifier.progress(running.size, spec.title, shown)
            }
        }
        if (r.intent != Intent.NONE) throw DownloadException(DownloadErrorKind.CANCELLED, "cancelled at end")

        return publish(id, spec, selection, subPlan, cur.copy(progress = 1f, speedBps = 0, etaSeconds = 0))
    }

    private fun publish(
        id: Long,
        spec: DownloadSpec,
        selection: FormatSelection?,
        plan: FormatPicker.SubtitlePlan,
        cur: DownloadEntity,
    ): DownloadEntity {
        val found = OutputFiles.resolve(storage.workFiles(id))
        val notes = mutableListOf<String>()
        val base = NamePolicy.baseName(spec.title)
        var mediaFile: PublishedFile? = null
        var mediaBase = base

        if (spec.kind != DownloadKind.SUBTITLES_ONLY) {
            val name = found.primary ?: throw DownloadException(DownloadErrorKind.ENGINE_FAILURE, "yt-dlp finished but produced no media file")
            val ext = name.substringAfterLast('.')
            mediaFile = storage.publish(base, ext, File(storage.workDir(id), name), OutputFiles.mimeType(ext))
            mediaBase = mediaFile.displayName.substringBeforeLast('.')
        }

        val subs = mutableListOf<String>()
        val wantedLangs = plan.available.map { it.first }
        for ((lang, fileName) in found.subtitles) {
            val want = wantedLangs.firstOrNull { it.language.equals(lang, true) } ?: continue
            val ext = fileName.substringAfterLast('.')
            val file = File(storage.workDir(id), fileName)
            val head = runCatching { file.readText().take(200_000) }.getOrDefault("")
            if (!OutputFiles.isValidSubtitle(ext, head)) { notes += "Subtitles (${want.language}) were invalid and skipped."; continue }
            val subName = NamePolicy.subtitleFileName(mediaBase, want.language, want.auto, ext).removeSuffix(".$ext")
            val p = storage.publish(subName, ext, file, OutputFiles.mimeType(ext))
            subs += "${want.language}|${p.uri}|${p.displayName}"
        }
        val producedLangs = found.subtitles.map { it.first.lowercase() }
        val missing = spec.subtitles.filter { s -> s !in wantedLangs || s.language.lowercase() !in producedLangs }
        if (missing.isNotEmpty() && notes.none { it.startsWith("Subtitles") }) {
            notes += "Subtitles not available: " + missing.joinToString { it.language }
        }
        if (spec.kind == DownloadKind.SUBTITLES_ONLY && subs.isEmpty()) {
            throw DownloadException(DownloadErrorKind.SUBTITLE_UNAVAILABLE, "no valid subtitle file produced")
        }

        storage.cleanWork(id)
        val mainUri = mediaFile?.uri ?: subs.firstOrNull()?.split('|')?.get(1)
        val mainName = mediaFile?.displayName ?: subs.firstOrNull()?.split('|')?.get(2)
        return cur.copy(
            state = DownloadState.COMPLETED.name,
            completedAt = System.currentTimeMillis(),
            destinationUri = mainUri,
            destinationName = mainName,
            mimeType = mediaFile?.mimeType ?: OutputFiles.mimeType(spec.subtitleFormat.extension),
            totalBytes = mediaFile?.sizeBytes ?: cur.totalBytes,
            downloadedBytes = mediaFile?.sizeBytes ?: cur.downloadedBytes,
            subtitleFiles = subs.joinToString("\n").ifEmpty { null },
            note = notes.joinToString(" ").ifEmpty { null },
            errorKind = null, errorMessage = null,
        )
    }

    // ───────────── helpers ─────────────

    private suspend fun mutate(id: Long, f: (DownloadEntity) -> DownloadEntity) {
        val e = dao.get(id) ?: return
        val n = f(e)
        if (n != e) dao.update(n)
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 700L
        /** No stdout line, no new bytes: the job is considered hung. */
        const val STALL_MS = 120_000L
        private const val MARGIN_BYTES = 50L * 1024 * 1024

        fun toSpec(e: DownloadEntity) = DownloadSpec(
            videoId = e.videoId,
            title = e.title,
            thumbnailUrl = e.thumbnailUrl,
            kind = DownloadKind.valueOf(e.kind),
            height = e.requestedHeight,
            audio = AudioChoice(e.audioLanguage, AudioChoice.AudioKind.valueOf(e.audioKind), e.audioLabel),
            subtitles = SubtitleSpec.decode(e.subtitleSpecs),
            subtitleFormat = SubtitleFormat.parse(e.subtitleFormat),
        )

        /** (language, uri, name) of finished subtitle files. */
        fun subtitleFileUris(e: DownloadEntity): List<Triple<String, String, String>> =
            e.subtitleFiles.orEmpty().lines().mapNotNull {
                val p = it.split('|')
                if (p.size == 3) Triple(p[0], p[1], p[2]) else null
            }
    }
}
