package com.openmeta.pipeline

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.ToneGenerator
import android.net.Uri
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class RenderMode(val titleRu: String, val descRu: String) {
    HORIZON_LOCK_2K("Горизонт 2K", "Стаба + выравнивание наклона головы + 2048×1536 + CAS"),
    META_FAST_2K("Быстрый 2K", "Легкая стаба + апскейл 2048×1536 + фильтр и резкость CAS"),
    RAW_ORIGINAL("Исходник 4:3", "Без улучшалок: прямое сохранение 1600×1200")
}

enum class ColorPreset(val titleRu: String) {
    META_WARM("Кино Meta"),
    DRIVE_HDR("Авто / Тени"),
    CRISP_DAY("Четкий HD")
}

@UnstableApi
object OpenMetaManager {
    private const val PREFS_NAME = "openmeta_ru_prefs"
    private const val KEY_RENDER_MODE = "render_mode"
    private const val KEY_COLOR_PRESET = "color_preset"
    private const val KEY_GEMINI_API = "gemini_api_key"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val videoQueue = Channel<VideoTask>(Channel.UNLIMITED)
    private var queueStarted = false

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val awaitingVisionPhoto = AtomicBoolean(false)
    private var pendingVisionQuestion: String? = null
    private var lastAiStatusText: String = "Удерживай сенсор дужки: вопрос голосом или «посмотри, что это»"
    private var renderQueueStatusText: String = ""

    private val visionTriggers = listOf(
        "посмотри", "смотри", "глянь", "взгляни", "видишь",
        "что это", "что тут", "что здесь", "что за", "что перед",
        "прочитай", "переведи", "оцени", "сфоткай", "на фото", "кадр", "впереди"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private data class VideoTask(val context: Context, val uri: Uri, val displayName: String)

    fun getRenderMode(context: Context): RenderMode {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RENDER_MODE, RenderMode.HORIZON_LOCK_2K.name)
        return runCatching { RenderMode.valueOf(name!!) }.getOrDefault(RenderMode.HORIZON_LOCK_2K)
    }

    fun setRenderMode(context: Context, mode: RenderMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RENDER_MODE, mode.name).apply()
    }

    fun getColorPreset(context: Context): ColorPreset {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COLOR_PRESET, ColorPreset.META_WARM.name)
        return runCatching { ColorPreset.valueOf(name!!) }.getOrDefault(ColorPreset.META_WARM)
    }

    fun setColorPreset(context: Context, preset: ColorPreset) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_COLOR_PRESET, preset.name).apply()
    }

    fun getGeminiKey(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GEMINI_API, "")?.trim().orEmpty()
    }

    fun setGeminiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_GEMINI_API, key.trim()).apply()
    }

    // 1. Прикрепление русского интерфейса Meta View поверх экрана MainActivity
    fun attach(
        activity: Activity,
        onStartSync: () -> Unit,
        onStopSync: () -> Unit,
        onScan: () -> Unit,
        onReconnect: () -> Unit,
        onTakePhoto: () -> Unit,
        onToggleVideo: () -> Unit,
        getState: () -> GlassesDashboardUiState
    ) {
        ensureVideoWorker()
        initTts(activity.applicationContext)

        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                activity.resources.displayMetrics
            ).toInt()
        }

        fun cardBg(colorHex: String, strokeHex: String = "#232936", radiusDp: Int = 18): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = dp(radiusDp).toFloat()
                setStroke(dp(1), Color.parseColor(strokeHex))
            }
        }

        val rootFrame = FrameLayout(activity)

        // Плавающая кнопка возврата из инженерного меню
        val btnReturnOverlay = Button(activity).apply {
            text = "← OpenMeta RU"
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            background = cardBg("#0064E0", "#3B82F6", 20)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }

        val scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.parseColor("#0B0D12"))
            isFillViewport = true
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(42), dp(18), dp(28))
        }
        scroll.addView(container)

        // --- ШАПКА ---
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(activity).apply {
            text = "Ray-Ban | Meta RU"
            setTextColor(Color.WHITE)
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val badgeStatus = TextView(activity).apply {
            text = "● Отключено"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            background = cardBg("#151922", "#262D3D", 14)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val btnLegacyUi = TextView(activity).apply {
            text = "⚙ Сток"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            background = cardBg("#151922", "#262D3D", 14)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(8)
            layoutParams = lp
            setOnClickListener {
                scroll.visibility = View.GONE
                btnReturnOverlay.visibility = View.VISIBLE
            }
        }
        btnReturnOverlay.setOnClickListener {
            scroll.visibility = View.VISIBLE
            btnReturnOverlay.visibility = View.GONE
        }
        headerRow.addView(titleView)
        headerRow.addView(badgeStatus)
        headerRow.addView(btnLegacyUi)
        container.addView(headerRow)

        // --- КАРТОЧКА ОЧКОВ ---
        val statusCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#151922")
            setPadding(dp(18), dp(16), dp(18), dp(16))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(16)
            layoutParams = lp
        }
        val tvDeviceInfo = TextView(activity).apply {
            text = "Очки не подключены"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        val tvBatteryAndPipeline = TextView(activity).apply {
            text = "Заряд: --% • Камера 1600×1200 (4:3)"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        }
        val connBtnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val btnScanUi = Button(activity).apply {
            text = "🔍 Поиск очков"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = cardBg("#1F2633", "#2E384D", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { onScan() }
        }
        val btnReconnectUi = Button(activity).apply {
            text = "🔄 Подключить"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = cardBg("#1F2633", "#2E384D", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) }
            setOnClickListener { onReconnect() }
        }
        connBtnRow.addView(btnScanUi)
        connBtnRow.addView(btnReconnectUi)
        statusCard.addView(tvDeviceInfo)
        statusCard.addView(tvBatteryAndPipeline)
        statusCard.addView(connBtnRow)
        container.addView(statusCard)

        // --- КАРТОЧКА ВЫГРУЗКИ И УЛУЧШЕНИЯ ВИДЕО ---
        val mediaCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#151922")
            setPadding(dp(18), dp(16), dp(18), dp(16))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14)
            layoutParams = lp
        }
        val tvMediaTitle = TextView(activity).apply {
            text = "Выгрузка и рендер 4:3 (1600×1200 → 2K)"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        val tvModeDesc = TextView(activity).apply {
            text = getRenderMode(activity).descRu
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        }

        // Кнопки выбора режима стабилизации / исходника
        val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val modeButtons = mutableMapOf<RenderMode, Button>()

        fun refreshModeButtons() {
            val current = getRenderMode(activity)
            tvModeDesc.text = current.descRu
            modeButtons.forEach { (mode, btn) ->
                val active = (mode == current)
                btn.background = if (active) cardBg("#0064E0", "#3B82F6", 12) else cardBg("#1F2633", "#2B3445", 12)
                btn.setTextColor(if (active) Color.WHITE else Color.parseColor("#94A3B8"))
            }
        }

        RenderMode.values().forEachIndexed { idx, mode ->
            val btn = Button(activity).apply {
                text = mode.titleRu
                textSize = 12f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (idx > 0) marginStart = dp(6)
                }
                setOnClickListener {
                    setRenderMode(activity, mode)
                    refreshModeButtons()
                }
            }
            modeButtons[mode] = btn
            modeRow.addView(btn)
        }

        // Кнопки выбора цветового пресета
        val presetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        val presetButtons = mutableMapOf<ColorPreset, Button>()

        fun refreshPresetButtons() {
            val current = getColorPreset(activity)
            presetButtons.forEach { (preset, btn) ->
                val active = (preset == current)
                btn.background = if (active) cardBg("#1E3A5F", "#38BDF8", 12) else cardBg("#1B202B", "#262D3D", 12)
                btn.setTextColor(if (active) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
            }
        }

        ColorPreset.values().forEachIndexed { idx, preset ->
            val btn = Button(activity).apply {
                text = preset.titleRu
                textSize = 11.5f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (idx > 0) marginStart = dp(6)
                }
                setOnClickListener {
                    setColorPreset(activity, preset)
                    refreshPresetButtons()
                }
            }
            presetButtons[preset] = btn
            presetRow.addView(btn)
        }

        refreshModeButtons()
        refreshPresetButtons()

        // Главная синяя кнопка скачивания
        val btnSyncMain = Button(activity).apply {
            text = "⬇ Скинуть и обработать медиа"
            setTextColor(Color.WHITE)
            textSize = 15f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = cardBg("#0064E0", "#3B82F6", 14)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                topMargin = dp(14)
            }
            setOnClickListener { onStartSync() }
        }

        val tvTransferStatus = TextView(activity).apply {
            text = "Готово к синхронизации"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12.5f
            setPadding(0, dp(8), 0, 0)
        }

        val btnStopSync = Button(activity).apply {
            text = "Остановить передачу"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 12f
            isAllCaps = false
            background = cardBg("#2A161B", "#7F1D1D", 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                topMargin = dp(8)
            }
            setOnClickListener { onStopSync() }
        }

        mediaCard.addView(tvMediaTitle)
        mediaCard.addView(tvModeDesc)
        mediaCard.addView(modeRow)
        mediaCard.addView(presetRow)
        mediaCard.addView(btnSyncMain)
        mediaCard.addView(tvTransferStatus)
        mediaCard.addView(btnStopSync)
        container.addView(mediaCard)

        // --- КАРТОЧКА РУССКОГО ИИ-АССИСТЕНТА (ПОСМОТРИ + ГОЛОС) ---
        val aiCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#151922")
            setPadding(dp(18), dp(16), dp(18), dp(16))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14)
            layoutParams = lp
        }
        val tvAiHeader = TextView(activity).apply {
            text = "Русский ИИ-ассистент (Голос + «Посмотри»)"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        val tvAiStatus = TextView(activity).apply {
            text = lastAiStatusText
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12.5f
            setPadding(0, dp(6), 0, dp(12))
        }
        val aiBtnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val btnVoiceAsk = Button(activity).apply {
            text = "🎙 Спросить"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = cardBg("#1F2633", "#2E384D", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                onGlassesTouchpadHold(activity)
            }
        }
        val btnVisionAsk = Button(activity).apply {
            text = "👁 Посмотри (Кадр)"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = cardBg("#1F2633", "#2E384D", 12)
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) }
            setOnClickListener {
                triggerVisionCapture(activity, "Что находится передо мной на этом кадре? Ответь коротко.")
            }
        }
        aiBtnRow.addView(btnVoiceAsk)
        aiBtnRow.addView(btnVisionAsk)

        val btnApiKey = Button(activity).apply {
            val hasKey = getGeminiKey(activity).isNotEmpty()
            text = if (hasKey) "🔑 Ключ Gemini сохранен (Изменить)" else "🔑 Ввести API-ключ Gemini"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12.5f
            isAllCaps = false
            background = cardBg("#111C2D", "#1E3A5F", 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                topMargin = dp(10)
            }
            setOnClickListener {
                showApiKeyDialog(activity) {
                    val updated = getGeminiKey(activity).isNotEmpty()
                    text = if (updated) "🔑 Ключ Gemini сохранен (Изменить)" else "🔑 Ввести API-ключ Gemini"
                }
            }
        }
        aiCard.addView(tvAiHeader)
        aiCard.addView(tvAiStatus)
        aiCard.addView(aiBtnRow)
        aiCard.addView(btnApiKey)
        container.addView(aiCard)

        // --- КАРТОЧКА БЫСТРЫХ ДЕЙСТВИЙ ---
        val quickCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14)
            layoutParams = lp
        }
        val btnPhotoQuick = Button(activity).apply {
            text = "📸 Фото"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = cardBg("#151922", "#262D3D", 14)
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(5) }
            setOnClickListener { onTakePhoto() }
        }
        val btnVideoQuick = Button(activity).apply {
            text = "🎥 Видео"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = cardBg("#151922", "#262D3D", 14)
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            }
            setOnClickListener { onToggleVideo() }
        }
        val btnGalleryQuick = Button(activity).apply {
            text = "🖼 Галерея"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = cardBg("#151922", "#262D3D", 14)
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(5) }
            setOnClickListener {
                runCatching {
                    activity.startActivity(Intent(activity, RecordingsListActivity::class.java))
                }
            }
        }
        quickCard.addView(btnPhotoQuick)
        quickCard.addView(btnVideoQuick)
        quickCard.addView(btnGalleryQuick)
        container.addView(quickCard)

        rootFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootFrame.addView(
            btnReturnOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(42)
                marginEnd = dp(16)
            }
        )

        activity.addContentView(
            rootFrame,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // Периодическое обновление статусов на русском
        val handler = Handler(Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                if (activity.isDestroyed || activity.isFinishing) return
                val connected = BleOperateManager.getInstance().isConnected
                val devName = DeviceManager.getInstance().deviceName?.takeIf { it.isNotBlank() } ?: "HeyCyan Glasses"
                val uiState = getState()
                val bat = uiState.batteryPercent?.let { "$it%" } ?: "--%"

                if (connected) {
                    badgeStatus.text = "● Подключено"
                    badgeStatus.setTextColor(Color.parseColor("#4ADE80"))
                    tvDeviceInfo.text = devName
                } else {
                    badgeStatus.text = "○ Отключено"
                    badgeStatus.setTextColor(Color.parseColor("#94A3B8"))
                    tvDeviceInfo.text = "Очки не подключены"
                }

                tvBatteryAndPipeline.text = "Заряд: $bat • Режим: ${getRenderMode(activity).titleRu} (${getColorPreset(activity).titleRu})"
                tvAiStatus.text = lastAiStatusText

                if (uiState.transfer.isVisible) {
                    btnStopSync.visibility = View.VISIBLE
                    val counts = uiState.transfer.countsLabel
                        .replace("Photos:", "Фото:")
                        .replace("Videos:", "Видео:")
                        .replace("Audio:", "Аудио:")
                    tvTransferStatus.text = "⏳ $counts\n${translateTransferDetail(uiState.transfer.detail)}"
                } else {
                    btnStopSync.visibility = View.GONE
                    tvTransferStatus.text = if (renderQueueStatusText.isNotEmpty()) {
                        renderQueueStatusText
                    } else {
                        "Готово к выгрузке (1600×1200 → 2048×1536)"
                    }
                }
                handler.postDelayed(this, 800L)
            }
        }
        handler.post(updater)
    }

    private fun translateTransferDetail(detail: String): String {
        return when {
            detail.contains("Starting", true) -> "Запуск Wi-Fi соединения с очками..."
            detail.contains("Waiting", true) -> "Ожидание точки доступа очков..."
            detail.contains("Fetching media", true) -> "Получение списка файлов..."
            detail.contains("Downloading photo", true) -> detail.replace("Downloading photo", "Скачивание фото")
            detail.contains("Downloading video", true) -> detail.replace("Downloading video", "Скачивание видео")
            detail.contains("Downloading audio", true) -> detail.replace("Downloading audio", "Скачивание аудио")
            detail.contains("Downloaded", true) -> detail.replace("Downloaded", "Скачано:")
            else -> detail
        }
    }

    private fun showApiKeyDialog(activity: Activity, onSaved: () -> Unit) {
        val input = EditText(activity).apply {
            hint = "AIzaSy..."
            setText(getGeminiKey(activity))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(activity)
            .setTitle("API-ключ Gemini")
            .setMessage("Вставь ключ из Google AI Studio для работы голосового ассистента и функции «Посмотри»:")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                setGeminiKey(activity, input.text.toString())
                onSaved()
                Toast.makeText(activity, "Ключ сохранен", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // 2. Перехват удержания сенсорной панели дужки (0x03) -> Русский ассистент + «Посмотри»
    fun onGlassesTouchpadHold(activity: Activity): Boolean {
        val apiKey = getGeminiKey(activity)
        if (apiKey.isEmpty()) {
            // Если ключ еще не введен — оставляем стандартное поведение приложения
            return false
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "Разреши доступ к микрофону для ИИ-ассистента", Toast.LENGTH_LONG).show()
            return false
        }

        // Останавливаем встроенный китайский голосовой режим очков
        runCatching {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x0b)) { _, _ -> }
        }

        activity.runOnUiThread {
            startRussianVoiceSession(activity)
        }
        return true
    }

    private fun startRussianVoiceSession(activity: Activity) {
        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startBluetoothMic(audioManager)
        playBeep()
        lastAiStatusText = "🎙 Слушаю вопрос с микрофона очков..."

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    stopBluetoothMic(audioManager)
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()

                    if (phrase.isEmpty()) {
                        lastAiStatusText = "Не удалось расслышать фразу"
                        speakRu(activity, "Не расслышал, повтори")
                        return
                    }

                    val lower = phrase.lowercase(Locale("ru", "RU"))
                    val needsVision = visionTriggers.any { lower.contains(it) }

                    if (needsVision) {
                        lastAiStatusText = "👁 Запрос со зрением: «$phrase» — делаю фото..."
                        speakRu(activity, "Секунду, смотрю")
                        triggerVisionCapture(activity, phrase)
                    } else {
                        lastAiStatusText = "💬 Вопрос: «$phrase» — думаю..."
                        scope.launch(Dispatchers.IO) {
                            val reply = askGeminiApi(activity, phrase, null)
                            withContext(Dispatchers.Main) {
                                lastAiStatusText = "🤖 Ответ: $reply"
                                speakRu(activity, reply)
                            }
                        }
                    }
                }

                override fun onError(error: Int) {
                    stopBluetoothMic(audioManager)
                    lastAiStatusText = "Ошибка распознавания ($error)"
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun triggerVisionCapture(activity: Activity, question: String) {
        if (!BleOperateManager.getInstance().isConnected) {
            speakRu(activity, "Очки не подключены по Блютуз")
            return
        }
        pendingVisionQuestion = question
        awaitingVisionPhoto.set(true)

        scope.launch(Dispatchers.IO) {
            runCatching {
                LargeDataHandler.getInstance().glassesControl(
                    byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02)
                ) { _, _ -> }
                delay(250)
                LargeDataHandler.getInstance().glassesControl(
                    byteArrayOf(0x02, 0x01, 0x01)
                ) { _, _ -> }
            }
            delay(5000)
            if (awaitingVisionPhoto.compareAndSet(true, false)) {
                withContext(Dispatchers.Main) {
                    lastAiStatusText = "Очки не прислали кадр, отвечаю без фото..."
                }
                val fallback = askGeminiApi(activity, question, null)
                withContext(Dispatchers.Main) {
                    lastAiStatusText = "🤖 Ответ: $fallback"
                    speakRu(activity, fallback)
                }
            }
        }
    }

    // 3. Перехват готовности ИИ-фото (0x02) -> скачивание кадра за 1.5 сек и отправка в Gemini
    fun onGlassesPhotoReady(activity: Activity): Boolean {
        if (!awaitingVisionPhoto.compareAndSet(true, false)) {
            return false
        }
        val question = pendingVisionQuestion ?: "Что на этом фото?"
        pendingVisionQuestion = null

        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                lastAiStatusText = "📥 Забираю кадр с очков..."
            }
            val imageBytes = fetchBlePictureBytes()
            val answer = askGeminiApi(activity, question, imageBytes)
            withContext(Dispatchers.Main) {
                lastAiStatusText = "🤖 Ответ: $answer"
                speakRu(activity, answer)
            }
        }
        return true
    }

    private suspend fun fetchBlePictureBytes(): ByteArray? {
        return withTimeoutOrNull(18_000L) {
            suspendCancellableCoroutine { cont ->
                val bos = ByteArrayOutputStream()
                val finished = AtomicBoolean(false)
                LargeDataHandler.getInstance().getPictureThumbnails { _, isComplete, data ->
                    if (data != null && data.isNotEmpty()) {
                        synchronized(bos) { bos.write(data) }
                    }
                    if (isComplete && finished.compareAndSet(false, true)) {
                        val bytes = bos.toByteArray()
                        if (cont.isActive) cont.resume(bytes.takeIf { it.isNotEmpty() })
                    }
                }
            }
        }
    }

    private fun askGeminiApi(context: Context, question: String, imageBytes: ByteArray?): String {
        val apiKey = getGeminiKey(context)
        if (apiKey.isEmpty()) return "Введи API-ключ Gemini на главном экране приложения."

        return try {
            val sysPrompt = "Ты русский голосовой ассистент, встроенный в умные очки водителя и мастера. " +
                "Отвечай на русском языке, коротко и по делу (максимум 2-3 предложения), без списков и символов разметки."

            val parts = JSONArray().apply {
                put(JSONObject().put("text", "$sysPrompt\n\nВопрос: $question"))
                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                        )
                    )
                }
            }

            val bodyJson = JSONObject().put(
                "contents",
                JSONArray().put(JSONObject().put("parts", parts))
            )

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return "Ошибка сервиса нейросети: код ${resp.code}"
                }
                JSONObject(raw)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .replace("*", "")
                    .replace("#", "")
                    .trim()
            }
        } catch (e: Exception) {
            Log.e("OpenMetaAI", "Gemini error", e)
            "Не удалось получить ответ от нейросети."
        }
    }

    // 4. Авто-улучшение скачанных фото и фоновая очередь 2K-рендера видео со стабой и горизонтом
    fun enhanceSavedJpegUri(context: Context, uri: Uri) {
        val mode = getRenderMode(context)
        if (mode == RenderMode.RAW_ORIGINAL) return
        val preset = getColorPreset(context)

        runCatching {
            val original = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return

            val contrast = when (preset) {
                ColorPreset.META_WARM -> 1.12f
                ColorPreset.DRIVE_HDR -> 1.06f
                ColorPreset.CRISP_DAY -> 1.16f
            }
            val brightnessShift = when (preset) {
                ColorPreset.DRIVE_HDR -> 14f // Вытягиваем темные тени приборки/подкапотки
                else -> (-0.5f * contrast + 0.5f) * 255f
            }

            val colorMatrix = ColorMatrix(
                floatArrayOf(
                    contrast * 1.04f, 0f, 0f, 0f, brightnessShift,
                    0f, contrast * 0.97f, 0f, 0f, brightnessShift,
                    0f, 0f, contrast * 1.02f, 0f, brightnessShift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            colorMatrix.postConcat(ColorMatrix().apply { setSaturation(1.22f) })

            val resultBitmap = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix)
            }
            Canvas(resultBitmap).drawBitmap(original, 0f, 0f, paint)

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
            }
            original.recycle()
            resultBitmap.recycle()
        }
    }

    fun enqueueVideoProcessingUri(context: Context, uri: Uri, displayName: String) {
        val mode = getRenderMode(context)
        if (mode == RenderMode.RAW_ORIGINAL) {
            renderQueueStatusText = "✅ Сохранен исходник 1600×1200: $displayName"
            return
        }
        ensureVideoWorker()
        renderQueueStatusText = "⏳ В очереди на 2K рендер и стабу: $displayName"
        videoQueue.trySend(VideoTask(context.applicationContext, uri, displayName))
    }

    private fun ensureVideoWorker() {
        if (queueStarted) return
        queueStarted = true
        scope.launch(Dispatchers.IO) {
            for (task in videoQueue) {
                processVideoTask(task)
            }
        }
    }

    private suspend fun processVideoTask(task: VideoTask) {
        val context = task.context
        val mode = getRenderMode(context)
        if (mode == RenderMode.RAW_ORIGINAL) return
        val preset = getColorPreset(context)

        val tempIn = File(context.cacheDir, "raw_${System.currentTimeMillis()}_${task.displayName}")
        val tempOut = File(context.cacheDir, "out_2k_${System.currentTimeMillis()}_${task.displayName}")

        try {
            renderQueueStatusText = "✨ Анализ горизонта и стабы: ${task.displayName}..."
            context.contentResolver.openInputStream(task.uri)?.use { input ->
                FileOutputStream(tempIn).use { output -> input.copyTo(output) }
            } ?: return

            val lockHorizon = (mode == RenderMode.HORIZON_LOCK_2K)
            val radius = if (lockHorizon) 12 else 8
            val trajectory = VideoStabilizerEffect.analyzeWithHorizonLock(tempIn, radius)

            renderQueueStatusText = "🚀 GPU-рендер 2K (2048×1536) + CAS: ${task.displayName}..."

            val renderedOk = suspendCancellableCoroutine<Boolean> { cont ->
                scope.launch(Dispatchers.Main) {
                    val videoEffects = listOf<Effect>(
                        VideoStabilizerEffect(
                            trajectory = trajectory,
                            baseZoom = 1.08f,
                            lockHorizon = lockHorizon
                        ),
                        Presentation.createForWidthAndHeight(
                            2048,
                            1536,
                            Presentation.LAYOUT_SCALE_TO_FIT
                        ),
                        MetaCinemaGlEffect(preset)
                    )

                    val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(tempIn)))
                        .setEffects(Effects(emptyList(), videoEffects))
                        .build()

                    val encoderSettings = VideoEncoderSettings.Builder()
                        .setBitrate(18_000_000)
                        .build()

                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(encoderSettings)
                        .build()

                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(encoderFactory)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onError(
                                composition: Composition,
                                result: ExportResult,
                                exception: ExportException
                            ) {
                                Log.e("OpenMetaVideo", "Render error", exception)
                                if (cont.isActive) cont.resume(false)
                            }
                        })
                        .build()

                    transformer.start(editedMediaItem, tempOut.absolutePath)
                }
            }

            if (renderedOk && tempOut.exists() && tempOut.length() > 0L) {
                context.contentResolver.openOutputStream(task.uri, "wt")?.use { outStream ->
                    tempOut.inputStream().use { inStream -> inStream.copyTo(outStream) }
                }
                renderQueueStatusText = "✅ Готово (2K 4:3 + ${mode.titleRu}): ${task.displayName}"
            } else {
                renderQueueStatusText = "⚠ Ошибка 2K-рендера, сохранен исходник: ${task.displayName}"
            }
        } catch (e: Exception) {
            Log.e("OpenMetaVideo", "Failed processing video task", e)
            renderQueueStatusText = "⚠ Сохранен исходник: ${task.displayName}"
        } finally {
            tempIn.delete()
            tempOut.delete()
        }
    }

    private fun initTts(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setSpeechRate(1.06f)
            }
        }
    }

    private fun speakRu(context: Context, text: String) {
        initTts(context.applicationContext)
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "OPENMETA_RU_${System.currentTimeMillis()}")
    }

    private fun startBluetoothMic(audioManager: AudioManager) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dev = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (dev != null) {
                    audioManager.setCommunicationDevice(dev)
                    return
                }
            }
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun stopBluetoothMic(audioManager: AudioManager) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun playBeep() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 85)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 250L)
        }
    }
}

@UnstableApi
class VideoStabilizerEffect(
    private val trajectory: List<FramePose>,
    private val frameDurationUs: Long = 33_333L,
    private val baseZoom: Float = 1.08f,
    private val lockHorizon: Boolean = true
) : MatrixTransformation {

    data class FramePose(val dx: Float, val dy: Float, val rollDeg: Float)

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val matrix = Matrix()
        if (trajectory.isEmpty()) {
            matrix.postScale(baseZoom, baseZoom)
            return matrix
        }

        val exactIndex = presentationTimeUs.toFloat() / frameDurationUs.toFloat()
        val index0 = exactIndex.toInt().coerceIn(0, trajectory.lastIndex)
        val index1 = (index0 + 1).coerceIn(0, trajectory.lastIndex)
        val fraction = exactIndex - index0

        val p0 = trajectory[index0]
        val p1 = trajectory[index1]

        val shiftX = p0.dx + (p1.dx - p0.dx) * fraction
        val shiftY = p0.dy + (p1.dy - p0.dy) * fraction
        val angleDeg = if (lockHorizon) {
            (p0.rollDeg + (p1.rollDeg - p0.rollDeg) * fraction).coerceIn(-14f, 14f)
        } else 0f

        val rad = Math.toRadians(abs(angleDeg).toDouble())
        val rotationSafeZoom = (cos(rad) + (4.0 / 3.0) * sin(rad)).toFloat()
        val totalZoom = max(baseZoom, rotationSafeZoom * 1.04f)

        val maxShift = baseZoom - 1.0f
        val clampedX = shiftX.coerceIn(-maxShift, maxShift)
        val clampedY = shiftY.coerceIn(-maxShift, maxShift)

        if (abs(angleDeg) > 0.05f) {
            matrix.postRotate(angleDeg)
        }
        matrix.postScale(totalZoom, totalZoom)
        matrix.postTranslate(clampedX, clampedY)

        return matrix
    }

    companion object {
        fun analyzeWithHorizonLock(videoFile: File, smoothingRadius: Int): List<FramePose> {
            val retriever = MediaMetadataRetriever()
            val rawPoses = mutableListOf<FramePose>()

            try {
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: return emptyList()

                var prevLuma: IntArray? = null
                var cumX = 0f
                var cumY = 0f
                var cumAngle = 0f

                var timeMs = 0L
                while (timeMs < durationMs) {
                    val bmp = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bmp != null) {
                        val small = Bitmap.createScaledBitmap(bmp, 96, 72, true)
                        bmp.recycle()
                        val currLuma = extractLuma(small)
                        small.recycle()

                        if (prevLuma != null) {
                            val (lx, ly) = matchZone(prevLuma, currLuma, 96, 72, 12, 44)
                            val (rx, ry) = matchZone(prevLuma, currLuma, 96, 72, 52, 84)

                            cumX += (lx + rx) * 0.5f
                            cumY += (ly + ry) * 0.5f

                            val deltaY = (ry - ly) * 36f
                            val deltaAngleDeg = Math.toDegrees(atan2(deltaY.toDouble(), 40.0)).toFloat()
                            cumAngle = (cumAngle + deltaAngleDeg) * 0.97f
                        }
                        prevLuma = currLuma
                    }
                    rawPoses.add(FramePose(cumX, cumY, cumAngle))
                    timeMs += 33L
                }
            } catch (_: Exception) {
                return emptyList()
            } finally {
                retriever.release()
            }

            return rawPoses.mapIndexed { i, current ->
