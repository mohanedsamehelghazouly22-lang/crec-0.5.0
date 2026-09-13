package com.mohaned.clashoverlay.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import com.mohaned.clashoverlay.capture.ScreenCaptureSource
import com.mohaned.clashoverlay.core.engine.CycleEngine
import com.mohaned.clashoverlay.core.engine.DeckTracker
import com.mohaned.clashoverlay.core.engine.ElixirEngine
import com.mohaned.clashoverlay.core.engine.HandTracker
import com.mohaned.clashoverlay.core.engine.TipsEngine
import com.mohaned.clashoverlay.core.model.GameState
import com.mohaned.clashoverlay.core.session.MatchSessionManager
import com.mohaned.clashoverlay.recognition.TfliteCardRecognizer
import com.mohaned.clashoverlay.recognition.VisionPipeline
import kotlin.math.max

class OverlayService : Service() {
    companion object {
        const val ACTION_STOP = "com.mohaned.clashoverlay.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
    }

    private var wm: WindowManager? = null
    private var overlay: OverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var projection: MediaProjection? = null
    private var capture: ScreenCaptureSource? = null
    private var pipeline: VisionPipeline? = null
    private var modelStatus = "MODEL REQUIRED"
    private var captureStatus = "STARTING"
    private var frameCount = 0
    private var fpsWindowMs = System.currentTimeMillis()
    private var fps = 0f

    private val deck = DeckTracker()
    private val hand = HandTracker()
    private val cycle = CycleEngine()
    private val elixir = ElixirEngine()
    private val tips = TipsEngine()
    private val session = MatchSessionManager(deck, hand, cycle, elixir)

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = OverlayView(this) { dx, dy -> moveOverlay(dx, dy) }
        addOverlay()
        renderIdle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            showError("SCREEN SHARE DENIED")
            stopSelf()
            return START_NOT_STICKY
        }

        if (capture != null) return START_NOT_STICKY

        return try {
            startAsForeground()
            startCapture(data)
            START_NOT_STICKY
        } catch (t: Throwable) {
            showError("CAPTURE ERROR: ${t.javaClass.simpleName}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("overlay", "Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this,
            9,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, "overlay")
            .setContentTitle("Clash Overlay Assistant")
            .setContentText("Observe only • on-device")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(Notification.Action.Builder(null, "STOP", stop).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                42,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(42, notification)
        }
    }

    private fun startCapture(data: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = manager.getMediaProjection(Activity.RESULT_OK, data)
            ?: error("MediaProjection unavailable")
        projection = p

        session.start()
        val recognizer = TfliteCardRecognizer(this)
        modelStatus = when (recognizer.availability) {
            TfliteCardRecognizer.Availability.READY -> "MODEL READY"
            TfliteCardRecognizer.Availability.MODEL_UNAVAILABLE -> "MODEL MISSING"
            TfliteCardRecognizer.Availability.LABELS_UNAVAILABLE -> "LABELS MISSING"
            TfliteCardRecognizer.Availability.INVALID_MODEL -> "MODEL INVALID"
        }
        pipeline = VisionPipeline(recognizer)

        val metrics = resources.displayMetrics
        captureStatus = "CAPTURE STARTING"
        publishStatus()

        capture = ScreenCaptureSource(
            projection = p,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            onFrame = { image ->
                try {
                    val now = System.currentTimeMillis()
                    frameCount++
                    updateFps(now)
                    captureStatus = "CAPTURE OK"

                    val observations = pipeline?.process(image, now).orEmpty()
                    observations.forEach { observation ->
                        deck.observe(observation)
                        if (observation.roiId.startsWith("opponent_hand")) {
                            hand.observe(observation)
                        }
                        if (observation.roiId.startsWith("opponent_play")) {
                            cycle.observePlayed(observation.cardId, observation.confidence)
                            elixir.observeSpent(estimateCost(observation.cardId))
                        }
                    }

                    cycle.updateFromHand(hand.snapshot())
                    val estimate = elixir.estimate()
                    val tip = tips.evaluate(
                        GameState(
                            matchId = session.matchId,
                            running = true,
                            opponentDeck = deck.snapshot(),
                            opponentHand = hand.snapshot(),
                            predictedNextCardId = cycle.predictedNext(deck.snapshot(), hand.snapshot()),
                            cycleIndex = cycle.index(),
                            cycleConfidence = cycle.confidence(),
                            elixir = estimate,
                            gamePhase = "BATTLE",
                            lastTip = null,
                            fps = fps,
                            recognitionLatencyMs = pipeline?.lastLatencyMs ?: 0L,
                            lastUpdatedMs = now,
                            captureStatus = captureStatus,
                            modelStatus = modelStatus
                        )
                    )

                    val state = GameState(
                        matchId = session.matchId,
                        running = true,
                        opponentDeck = deck.snapshot(),
                        opponentHand = hand.snapshot(),
                        predictedNextCardId = cycle.predictedNext(deck.snapshot(), hand.snapshot()),
                        cycleIndex = cycle.index(),
                        cycleConfidence = cycle.confidence(),
                        elixir = estimate,
                        gamePhase = "BATTLE",
                        lastTip = tip?.text,
                        fps = fps,
                        recognitionLatencyMs = pipeline?.lastLatencyMs ?: 0L,
                        lastUpdatedMs = now,
                        captureStatus = captureStatus,
                        modelStatus = modelStatus
                    )
                    overlay?.post { overlay?.render(state) }
                } finally {
                    runCatching { image.close() }
                }
            },
            onStopped = {
                captureStatus = "CAPTURE STOPPED"
                overlay?.post { renderIdle() }
                stopSelf()
            }
        )
        publishStatus()
    }

    private fun publishStatus() {
        overlay?.post {
            overlay?.render(
                GameState(
                    matchId = session.matchId.ifBlank { "starting" },
                    running = captureStatus == "CAPTURE OK",
                    elixir = elixir.estimate(),
                    fps = fps,
                    captureStatus = captureStatus,
                    modelStatus = modelStatus
                )
            )
        }
    }

    private fun renderIdle() {
        overlay?.render(
            GameState(
                matchId = "idle",
                running = false,
                captureStatus = captureStatus,
                modelStatus = modelStatus
            )
        )
    }

    private fun showError(message: String) {
        captureStatus = message
        overlay?.post { renderIdle() }
    }

    private fun estimateCost(cardId: String): Int = when (cardId) {
        "skeletons" -> 1
        "goblins" -> 2
        "knight", "archers", "arrows", "little-prince" -> 3
        "fireball", "hog-rider", "musketeer", "golden-knight", "skeleton-king", "mighty-miner" -> 4
        "archer-queen", "monk", "goblinstein" -> 5
        "boss-bandit" -> 6
        else -> 4
    }

    private fun updateFps(now: Long) {
        val elapsed = now - fpsWindowMs
        if (elapsed >= 1000L) {
            fps = frameCount * 1000f / elapsed
            frameCount = 0
            fpsWindowMs = now
        }
    }

    private fun addOverlay() {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        params = WindowManager.LayoutParams(
            360,
            150,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 180
        }
        runCatching { wm?.addView(overlay, params) }
            .onFailure { showError("OVERLAY ERROR") }
    }

    private fun moveOverlay(dx: Int, dy: Int) {
        val p = params ?: return
        p.x = max(0, p.x - dx)
        p.y = max(0, p.y + dy)
        runCatching { wm?.updateViewLayout(overlay, p) }
    }

    override fun onDestroy() {
        runCatching { capture?.close() }
        capture = null
        runCatching { pipeline?.close() }
        pipeline = null
        runCatching { projection?.stop() }
        projection = null
        session.end()
        overlay?.let { runCatching { wm?.removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
