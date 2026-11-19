package ru.screentranslator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Captures the screen with MediaProjection, reads text inside the selected area,
 * translates only text that changed, and draws the result over the screen.
 */
class ScreenTranslatorService : Service() {

    companion object {
        const val ACTION_START = "ru.screentranslator.START"
        const val ACTION_SELECT = "ru.screentranslator.SELECT"
        const val ACTION_STOP = "ru.screentranslator.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "translator"
        private const val NOTIF_ID = 1
        private const val CHUNK_SIZE = 25
        private const val TICK_DELAY_MS = 1200L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()

    private lateinit var settings: AppSettings
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: TranslationOverlay? = null
    private var selection: SelectionOverlay? = null
    private var ocr: OcrEngine? = null
    private var translator: Translator? = null
    private var loopJob: Job? = null
    private var lastSignature = ""
    private var lastError = ""

    /** "lang|source text" -> translation, least recently used entries are dropped. */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 2000
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_SELECT -> selectArea()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        startAsForeground() // must happen right away, before any early return
        if (_running.value) return

        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) {
            stopSelf()
            return
        }

        val mp = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, null)
        projection = mp

        val metrics = realDisplayMetrics()
        val r = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        reader = r
        virtualDisplay = mp.createVirtualDisplay(
            "screen-translator",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface,
            null,
            null,
        )

        overlay = TranslationOverlay(this).also { it.show() }
        ocr = OcrEngine(settings.ocrScript)
        translator = buildTranslator(settings, http)
        _running.value = true

        if (settings.region == null) selectArea() else startLoop()
    }

    @Suppress("DEPRECATION")
    private fun realDisplayMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun selectArea() {
        if (selection != null) return
        selection = SelectionOverlay(this) { region ->
            selection = null
            if (region != null) {
                settings.region = region
                startLoop()
            }
        }.also { it.show() }
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { translationLoop() }
    }

    private suspend fun translationLoop() {
        while (true) {
            val region = settings.region
            if (region != null) {
                try {
                    tick(region)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showError(e.message ?: e.javaClass.simpleName)
                }
            }
            delay(TICK_DELAY_MS)
        }
    }

    private suspend fun tick(region: Region) {
        val engine = ocr ?: return
        val tr = translator ?: return

        val bitmap = captureRegion(region) ?: return
        val blocks = engine.recognize(bitmap)
        bitmap.recycle()

        if (blocks.isEmpty()) {
            lastSignature = ""
            overlay?.update(emptyList())
            return
        }

        // Nothing changed on screen since last tick: skip the API entirely.
        val signature = blocks.joinToString("\n") { "${it.box.flattenToString()}|${it.text}" }
        if (signature == lastSignature) return

        val lang = settings.targetLang
        val missing = blocks.map { it.text }.distinct().filter { cache["$lang|$it"] == null }
        for (chunk in missing.chunked(CHUNK_SIZE)) {
            val result = tr.translate(chunk, lang)
            chunk.zip(result.translations).forEach { (src, dst) -> cache["$lang|$src"] = dst }
        }

        lastSignature = signature
        lastError = ""
        overlay?.update(blocks.map { b ->
            OverlayItem(
                Rect(b.box).apply { offset(region.left, region.top) },
                cache["$lang|${b.text}"] ?: b.text,
            )
        })
    }

    /** Returns only the selected area, as a bitmap. */
    private suspend fun captureRegion(region: Region): Bitmap? {
        val r = reader ?: return null
        overlay?.setShown(false)
        delay(150) // give the screen one frame without our overlay
        val image = r.acquireLatestImage()
        overlay?.setShown(true)
        image ?: return null

        return image.use { img ->
            val plane = img.planes[0]
            val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)

            val left = region.left.coerceIn(0, full.width - 1)
            val top = region.top.coerceIn(0, full.height - 1)
            val w = (minOf(region.right, full.width) - left).coerceAtLeast(1)
            val h = (minOf(region.bottom, full.height) - top).coerceAtLeast(1)
            Bitmap.createBitmap(full, left, top, w, h)
        }
    }

    private suspend fun showError(message: String) {
        if (message == lastError) return
        lastError = message
        withContext(Dispatchers.Main) {
            Toast.makeText(this@ScreenTranslatorService, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startAsForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Перевод экрана", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Перевод экрана работает")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(0, "Выбрать область", actionIntent(ACTION_SELECT))
            .addAction(0, "Стоп", actionIntent(ACTION_STOP))
            .build()
        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, ScreenTranslatorService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        selection?.dismiss()
        selection = null
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        overlay?.remove()
        overlay = null
        ocr?.close()
        ocr = null
        _running.value = false
        super.onDestroy()
    }
}
