package com.primenimoo.handhack.engine

import com.primenimoo.handhack.engine.capture.CaptureLoader
import com.primenimoo.handhack.engine.handshake.HandshakeTarget
import com.primenimoo.handhack.engine.handshake.Hex
import com.primenimoo.handhack.engine.handshake.Pbkdf2
import com.primenimoo.handhack.engine.handshake.PmkVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Real local analyser.
 *
 * Design goals, in order:
 *  1. Never fake anything. HandHack's only job is a WPA handshake dictionary attack:
 *     every reported password is a real PBKDF2-HMAC-SHA1(4096) derivation that a
 *     [PmkVerifier] accepted against the capture. Progress, counters, throughput and ETA
 *     all come from that loop.
 *  2. Real stop semantics. [cancel] always leaves the engine in a terminal
 *     [AnalysisStatus.CANCELLED] state with a result, so the UI can never hang on
 *     "Остановка…" and the aborted run still reaches history.
 *  3. Bounded memory. The dictionary is prepared once and workers pull candidates from a
 *     single cursor, so memory does not grow with the file size.
 *  4. Cooperative pause and cancellation, both checked inside the hot loop.
 *  5. Modularity — the UI only ever sees [AnalysisEngine].
 */
class LocalAnalysisEngine(
    private val scope: CoroutineScope,
    private val dictionaryProvider: suspend () -> Set<String>,
) : AnalysisEngine {

    private val _status = MutableStateFlow(AnalysisStatus.IDLE)
    private val _progress = MutableStateFlow(AnalysisProgress())
    private val _statistics = MutableStateFlow(AnalysisStatistics())
    private val _result = MutableStateFlow<AnalysisResult?>(null)
    private val _currentRequest = MutableStateFlow<AnalysisRequest?>(null)

    override val status: StateFlow<AnalysisStatus> = _status.asStateFlow()
    override val progress: StateFlow<AnalysisProgress> = _progress.asStateFlow()
    override val statistics: StateFlow<AnalysisStatistics> = _statistics.asStateFlow()
    override val result: StateFlow<AnalysisResult?> = _result.asStateFlow()
    override val currentRequest: StateFlow<AnalysisRequest?> = _currentRequest.asStateFlow()

    private var job: Job? = null
    private val pauseGate = MutableStateFlow(false)
    private var prepared: AnalysisRequest? = null
    private var dictionary: Set<String> = emptySet()
    private var totalItems: Long = 0L

    /** Non-empty when the file is a captured handshake instead of a plain record list. */
    private var targets: List<HandshakeTarget> = emptyList()

    /** What the readers said about the records they could not use, shown if nothing is checkable. */
    private var captureNotes: List<String> = emptyList()

    /** Candidates that pass the WPA passphrase length rule, prepared once per run. */
    private var candidates: List<String> = emptyList()

    override suspend fun prepare(request: AnalysisRequest) {
        stopRunningJob()
        _result.value = null
        _currentRequest.value = request
        _status.value = AnalysisStatus.PREPARING
        _progress.value = AnalysisProgress(indeterminate = true, stageLabel = "Читаем словарь…")
        _statistics.value = AnalysisStatistics(workers = max(1, request.workers))

        try {
            dictionary = dictionaryProvider()
            prepared = request
            targets = emptyList()
            candidates = emptyList()
            captureNotes = emptyList()

            _progress.update { it.copy(stageLabel = "Разбираем хендшейк…") }
            val loaded = withContext(Dispatchers.IO) {
                val file = File(request.sourcePath)
                if (!file.exists()) throw IllegalStateException("Файл больше недоступен.")
                CaptureLoader.load(file)
            }
            targets = loaded.targets
            captureNotes = loaded.notes
            // The container decides how the file is described, so the result screen shows the
            // format that was actually read rather than the one the extension suggested.
            _currentRequest.value = request.copy(
                formatLabel = loaded.format.label,
                textBased = loaded.format.textBased,
            )

            if (dictionary.isEmpty()) {
                fail("Словарь пуст. Импортируйте записи или восстановите словарь по умолчанию.")
                return
            }

            // A handshake is verified by deriving keys from dictionary candidates, so the
            // candidate list — not the file lines — is the work unit of a run.
            candidates = dictionary.filter { it.length in WPA_MIN_PASSPHRASE..WPA_MAX_PASSPHRASE }.toList()
            if (candidates.isEmpty()) {
                fail(
                    "В словаре нет записей длиной $WPA_MIN_PASSPHRASE–$WPA_MAX_PASSPHRASE " +
                        "символов, пригодных для проверки.",
                )
                return
            }

            if (targets.isEmpty()) {
                // Say exactly what is missing instead of silently reporting a zero-match run:
                // each reader already knows why it dropped what it dropped.
                fail(
                    captureNotes.firstOrNull()
                        ?: "В файле нет ни одной записи, по которой можно проверить пароль. " +
                        "HandHack читает записи WPA*01 и WPA*02, а также hccapx, hccap, pcap " +
                        "и pcapng с EAPOL-Key message 2.",
                )
                return
            }
            totalItems = candidates.size.toLong()

            _progress.value = AnalysisProgress(
                processed = 0L,
                total = totalItems,
                fraction = 0f,
                indeterminate = false,
                stageLabel = "Готово к запуску",
            )
            _status.value = AnalysisStatus.IDLE
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            fail(error.message ?: "Не удалось подготовить файл.")
        }
    }

    override suspend fun start() {
        val request = prepared ?: return fail("Сначала выберите файл.")
        if (_status.value.isActive || _status.value == AnalysisStatus.PAUSED) return

        pauseGate.value = false
        _result.value = null
        _status.value = AnalysisStatus.RUNNING
        _progress.value = AnalysisProgress(total = totalItems, fraction = 0f, stageLabel = "Запуск…")

        job = scope.launch(Dispatchers.Default) {
            val startedAt = System.nanoTime()
            val processed = AtomicLong()
            val recovered = ConcurrentHashMap<String, String>()
            val workers = max(1, min(request.workers, MAX_WORKERS))

            try {
                coroutineScope {
                    val sampler = launch(Dispatchers.Default) {
                        sample(processed, totalItems, workers, startedAt)
                    }
                    crack(workers, processed, recovered)
                    sampler.cancel()
                }

                val elapsed = elapsedMillis(startedAt)
                val finalProcessed = processed.get()
                val solved = recovered.entries
                    .sortedBy { it.key }
                    .map { RecoveredPassword(ssid = displaySsidOf(it.key), password = it.value) }
                val passwords = solved.map { it.password }

                _statistics.value = AnalysisStatistics(
                    processed = finalProcessed,
                    total = totalItems,
                    itemsPerSecond = rateFor(finalProcessed, elapsed),
                    elapsedMillis = elapsed,
                    remainingMillis = 0L,
                    workers = workers,
                    matchedItems = solved.size,
                )
                _progress.value = AnalysisProgress(
                    processed = finalProcessed,
                    total = totalItems,
                    fraction = 1f,
                    indeterminate = false,
                    stageLabel = if (passwords.isEmpty()) "Совпадений не найдено" else "Готово",
                )

                val status = if (passwords.isEmpty()) AnalysisStatus.NO_RESULT else AnalysisStatus.COMPLETED
                _status.value = status
                _result.value = AnalysisResult(
                    status = status,
                    fileName = request.fileName,
                    formatLabel = request.formatLabel,
                    sizeBytes = request.sizeBytes,
                    totalItems = totalItems,
                    processedItems = finalProcessed,
                    durationMillis = elapsed,
                    workersUsed = workers,
                    matchedEntries = passwords,
                    recoveredPasswords = solved,
                    errorMessage = null,
                    failureReason = if (passwords.isEmpty()) {
                        "Ни один пароль из словаря не подошёл к сетям в файле"
                    } else {
                        null
                    },
                )
            } catch (cancellation: CancellationException) {
                // A cancelled run still has to reach a terminal state, otherwise the UI
                // would sit on "Остановка…" forever and history would never record it.
                finishCancelled(request, startedAt, processed.get())
                throw cancellation
            } catch (error: Throwable) {
                fail(error.message ?: "Не удалось выполнить подбор.")
            }
        }
    }

    /**
     * Publishes a terminal CANCELLED result so the caller can release the UI and record
     * the aborted run in history.
     */
    private fun finishCancelled(request: AnalysisRequest, startedAt: Long, processed: Long) {
        _status.value = AnalysisStatus.CANCELLED
        _progress.update {
            it.copy(
                processed = processed,
                fraction = 0f,
                indeterminate = false,
                stageLabel = "Подбор отменён",
            )
        }
        val elapsed = elapsedMillis(startedAt)
        _statistics.value = AnalysisStatistics(
            processed = processed,
            total = totalItems,
            itemsPerSecond = rateFor(processed, elapsed),
            elapsedMillis = elapsed,
            remainingMillis = 0L,
            workers = max(1, min(request.workers, MAX_WORKERS)),
            matchedItems = _result.value?.recoveredPasswords?.size ?: 0,
        )
        _result.value = AnalysisResult(
            status = AnalysisStatus.CANCELLED,
            fileName = request.fileName,
            formatLabel = request.formatLabel,
            sizeBytes = request.sizeBytes,
            totalItems = totalItems,
            processedItems = processed,
            durationMillis = elapsed,
            workersUsed = max(1, min(request.workers, MAX_WORKERS)),
            matchedEntries = emptyList(),
            recoveredPasswords = emptyList(),
            errorMessage = null,
            failureReason = "Подбор отменён пользователем",
        )
    }

    /**
     * Dictionary attack against every captured network.
     *
     * Each worker pulls the next candidate from a shared cursor and derives
     * PBKDF2-HMAC-SHA1(passphrase, ssid, 4096) once per distinct SSID. The derived key is
     * then handed to the target's [PmkVerifier], which knows whether the capture stored the
     * master key itself, only its PMKID, or the MIC of message 2. The attack stops as soon
     * as every network in the file has a confirmed password, so a small dictionary finishes
     * instantly.
     *
     * Networks are grouped by their raw SSID bytes rather than by a decoded string: a network
     * name is arbitrary octets, and a name that is not valid UTF-8 would otherwise derive a
     * different key than the one the capture was made with.
     */
    private suspend fun crack(
        workers: Int,
        processed: AtomicLong,
        recovered: ConcurrentHashMap<String, String>,
    ) {
        val groups = targets.groupBy { Hex.encode(it.ssidBytes) }
        val ssidBytesOf = groups.mapValues { (_, list) -> list.first().ssidBytes }
        val cursor = AtomicInteger()

        coroutineScope {
            List(workers) { index ->
                launch(Dispatchers.Default) {
                    while (recovered.size < groups.size) {
                        awaitIfPaused()
                        val position = cursor.getAndIncrement()
                        if (position >= candidates.size) break
                        val candidate = candidates[position]
                        val passphrase = candidate.toByteArray(Charsets.UTF_8)

                        for ((key, networkTargets) in groups) {
                            if (recovered.containsKey(key)) continue
                            val derived = Pbkdf2.derive(passphrase, ssidBytesOf.getValue(key))
                            for (target in networkTargets) {
                                if (target.verifier.accepts(derived)) {
                                    recovered.putIfAbsent(key, candidate)
                                }
                            }
                        }

                        processed.incrementAndGet()
                    }
                }
            }
        }
    }

    /** Printable name for a recovered network, looked up by the hex key used during the run. */
    private fun displaySsidOf(key: String): String =
        targets.firstOrNull { Hex.encode(it.ssidBytes) == key }?.displaySsid ?: key

    override suspend fun pause() {
        if (_status.value == AnalysisStatus.RUNNING) {
            pauseGate.value = true
            _status.value = AnalysisStatus.PAUSED
            _progress.update { it.copy(stageLabel = "Приостановлено") }
        }
    }

    override suspend fun resume() {
        if (_status.value == AnalysisStatus.PAUSED) {
            pauseGate.value = false
            _status.value = AnalysisStatus.RUNNING
            _progress.update { it.copy(stageLabel = "Продолжаем…") }
        }
    }

    override suspend fun cancel() {
        val wasActive = _status.value.isActive || _status.value == AnalysisStatus.PAUSED
        if (!wasActive) {
            // Nothing is running, but make sure no stale state keeps the UI disabled.
            pauseGate.value = false
            job = null
            return
        }

        // Release the pause gate first: a paused worker sitting in awaitIfPaused must not
        // delay the cancellation, and the button must become usable again immediately.
        pauseGate.value = false

        val running = job
        if (running == null) {
            // The job reference was lost but the status still claims to be running.
            finishCancelled(prepared ?: return, System.nanoTime(), _progress.value.processed)
            return
        }

        running.cancel(CancellationException("Отменено пользователем"))
        // Give the workers a bounded window to unwind, then force a terminal state so
        // the UI can never be left waiting on a job that will not report back.
        val unwound = withTimeoutOrNull(CANCEL_GRACE_MS) { runCatching { running.join() } } != null
        if (!unwound || _status.value.isActive || _status.value == AnalysisStatus.PAUSED) {
            finishCancelled(prepared ?: return, System.nanoTime(), _progress.value.processed)
        }
        job = null
    }

    override fun reset() {
        job?.cancel()
        job = null
        pauseGate.value = false
        prepared = null
        dictionary = emptySet()
        totalItems = 0L
        targets = emptyList()
        candidates = emptyList()
        captureNotes = emptyList()
        _status.value = AnalysisStatus.IDLE
        _progress.value = AnalysisProgress()
        _statistics.value = AnalysisStatistics()
        _result.value = null
        _currentRequest.value = null
    }

    private suspend fun stopRunningJob() {
        val running = job ?: return
        if (_status.value.isActive || _status.value == AnalysisStatus.PAUSED) {
            running.cancel(CancellationException("Смена файла"))
            runCatching { running.join() }
        }
        job = null
        pauseGate.value = false
    }

    private suspend fun awaitIfPaused() {
        if (!pauseGate.value) return
        while (pauseGate.value) {
            delay(POLL_INTERVAL_MS)
            yield()
        }
    }

    private suspend fun sample(
        processed: AtomicLong,
        total: Long,
        workers: Int,
        startedAt: Long,
    ) {
        var smoothedRate = 0.0
        var lastProcessed = 0L
        var lastAt = System.nanoTime()
        while (true) {
            delay(SAMPLE_INTERVAL_MS)
            val now = System.nanoTime()
            val done = processed.get()
            val deltaSeconds = (now - lastAt) / 1_000_000_000.0
            if (deltaSeconds > 0.0) {
                val instant = (done - lastProcessed) / deltaSeconds
                smoothedRate = if (smoothedRate == 0.0) instant else smoothedRate * 0.6 + instant * 0.4
            }
            lastProcessed = done
            lastAt = now

            val fraction = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            _progress.value = AnalysisProgress(
                processed = done,
                total = total,
                fraction = fraction,
                indeterminate = total <= 0L,
                stageLabel = stageLabel(fraction),
            )
            _statistics.value = AnalysisStatistics(
                processed = done,
                total = total,
                itemsPerSecond = smoothedRate,
                elapsedMillis = elapsedMillis(startedAt),
                remainingMillis = remainingMillis(done, total, smoothedRate),
                workers = workers,
            )
        }
    }

    private fun stageLabel(fraction: Float): String = when {
        fraction >= 1f -> "Финальная проверка"
        fraction > 0.75f -> "Последние пароли"
        fraction > 0.5f -> "Проверяем пароли"
        fraction > 0.25f -> "Подбор по словарю"
        fraction > 0f -> "Запускаем рабочие потоки"
        else -> "Запуск…"
    }

    private fun remainingMillis(done: Long, total: Long, rate: Double): Long {
        if (rate <= 0.0 || total <= 0L) return -1L
        return (((total - done).coerceAtLeast(0L)) / rate * 1000.0).roundToLong()
    }
    private fun rateFor(processed: Long, elapsedMillis: Long): Double {
        if (elapsedMillis <= 0L) return 0.0
        return processed * 1000.0 / elapsedMillis
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun fail(message: String) {
        _status.value = AnalysisStatus.ERROR
        _progress.update { it.copy(indeterminate = false, stageLabel = "Прервано") }
        val request = prepared
        _result.value = AnalysisResult(
            status = AnalysisStatus.ERROR,
            fileName = request?.fileName ?: "—",
            formatLabel = request?.formatLabel ?: "—",
            sizeBytes = request?.sizeBytes ?: 0L,
            totalItems = totalItems,
            processedItems = _progress.value.processed,
            durationMillis = _statistics.value.elapsedMillis,
            workersUsed = max(1, request?.workers ?: 1),
            errorMessage = message,
        )
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 60L
        const val POLL_INTERVAL_MS = 40L
        const val MAX_WORKERS = 32

        /** How long cancel() waits for the workers to unwind before forcing a result. */
        const val CANCEL_GRACE_MS = 3000L

        /** IEEE 802.11i passphrase length limits. */
        const val WPA_MIN_PASSPHRASE = 8
        const val WPA_MAX_PASSPHRASE = 63

        /** Suggested worker count for the chosen performance preference. */
        fun suggestedWorkers(mode: String, manual: Int, cores: Int): Int = when (mode) {
            "eco" -> max(1, cores / 4)
            "balanced" -> max(1, cores / 2)
            "maximum" -> max(1, cores - 1)
            "limited" -> manual.coerceIn(1, MAX_WORKERS)
            else -> max(1, min(cores, 4))
        }
    }
}