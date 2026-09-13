package com.mohaned.clashoverlay.capture

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread

/**
 * Owns exactly one MediaProjection -> VirtualDisplay capture session.
 * Android 14+ requires a MediaProjection.Callback before createVirtualDisplay().
 */
class ScreenCaptureSource(
    private val projection: MediaProjection,
    width: Int,
    height: Int,
    densityDpi: Int,
    private val onFrame: (Image) -> Unit,
    private val onStopped: () -> Unit = {}
) : ImageReader.OnImageAvailableListener, AutoCloseable {

    private val thread = HandlerThread("capture-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(
        width.coerceAtLeast(1),
        height.coerceAtLeast(1),
        PixelFormat.RGBA_8888,
        3
    )
    private var display: VirtualDisplay? = null
    @Volatile private var closed = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            closeInternal(unregisterCallback = false)
            onStopped()
        }
    }

    init {
        // REQUIRED on Android 14+ and must happen before createVirtualDisplay().
        projection.registerCallback(projectionCallback, handler)
        reader.setOnImageAvailableListener(this, handler)

        try {
            display = projection.createVirtualDisplay(
                "ClashOverlayCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (t: Throwable) {
            closeInternal(unregisterCallback = true)
            throw t
        }
    }

    override fun onImageAvailable(reader: ImageReader) {
        if (closed) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            onFrame(image)
        } catch (_: Throwable) {
            // The owner is responsible for closing the Image in its finally block.
        }
    }

    override fun close() = closeInternal(unregisterCallback = true)

    private fun closeInternal(unregisterCallback: Boolean) {
        if (closed) return
        closed = true

        if (unregisterCallback) {
            runCatching { projection.unregisterCallback(projectionCallback) }
        }
        runCatching { display?.release() }
        display = null
        runCatching { reader.setOnImageAvailableListener(null, null) }
        runCatching { reader.close() }
        thread.quitSafely()
    }
}
