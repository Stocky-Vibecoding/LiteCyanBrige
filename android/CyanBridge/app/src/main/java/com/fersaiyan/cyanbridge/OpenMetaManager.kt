package com.fersaiyan.cyanbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
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
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
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
import com.fersaiyan.cyanbridge.devices.tunebuds.TuneBudsManager
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.media.SyncedMediaFolder
import com.fersaiyan.cyanbridge.ui.recordings.RecordingsListActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class OpenMetaRenderMode(val id: Int, val title: String, val subtitle: String) {
    RAW_ORIGINAL(
        0,
        "Исходник 1600×1200",
        "Без обработки: быстрое сохранение с очков как есть"
    ),
    META_FAST_2K(
        1,
        "Быстрый Meta 2K (4:3)",
        "Апскейл 2048×1536 • Резкость CAS • Кино-цвет • Легкая стаба"
    ),
    HORIZON_LOCK_2K(
        2,
        "Горизонт Lock 2K (4:3)",
        "Выравнивание горизонта при наклоне головы • Полная стаба • 2K"
    );

    companion object {
        fun fromId(id: Int): OpenMetaRenderMode = entries.firstOrNull { it.id == id } ?: META_FAST_2K
    }
}

@OptIn(UnstableApi::class)
class OpenMetaManager(
    private val activity: Activity,
    private val onSyncClick: () -> Unit,
    private val onScanClick: () -> Unit,
    private val onPhotoClick: () -> Unit,
    private val onVideoClick: () -> Unit,
    private val onVisionPhotoRequest: () -> Unit
) {
    private val prefs = activity.getSharedPreferences("openmeta_ru_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaSession: MediaSession? = null
    private var lastTriggerTimeMs = 0L

    private var statusBadgeView: TextView? = null
    private var batteryTextView: TextView? = null
    private var statusSubtitleView: TextView? = null
    private var geminiBtnView: Button? = null
    private val modeButtons = mutableListOf<Button>()

    private data class QueuedVideoJob(
        val rawFile: File,
        val displayName: String,
        val mode: OpenMetaRenderMode
    )

    private val renderChannel = Channel<QueuedVideoJob>(Channel.UNLIMITED)
    private val pendingRenderCount = AtomicInteger(0)

    var lastUserVisionQuery: String = "Коротко опиши, что перед пользователем на этом кадре."

    private val visionTriggers = listOf(
        "посмотри", "смотри", "глянь", "взгляни", "видишь",
        "что это", "сто это", "че это", "чё это", "что тут",
        "что здесь", "что за", "что перед", "кто это",
        "прочитай", "переведи", "оцени", "сфоткай", "сфотай", "на фото", "покажи"
    )

    var currentRenderMode: OpenMetaRenderMode
        get() = OpenMetaRenderMode.fromId(prefs.getInt("render_mode", OpenMetaRenderMode.META_FAST_2K.id))
        set(value) {
            prefs.edit().putInt("render_mode", value.id).apply()
            updateModeButtonsUi()
        }

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) {
            prefs.edit().putString("gemini_api_key", value.trim()).apply()
            updateGeminiButtonText()
        }

    var geminiBaseUrl: String
        get() = prefs.getString("gemini_base_url", "https://generativelanguage.googleapis.com")
            ?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: "https://generativelanguage.googleapis.com"
        set(value) {
            val clean = value.trim().trimEnd('/').ifEmpty { "https://generativelanguage.googleapis.com" }
            prefs.edit().putString("gemini_base_url", clean).apply()
        }

    init {
        tts = TextToSpeech(activity.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setSpeechRate(1.06f)
            }
        }
        setupMediaSessionInterceptor()
        startBackgroundRenderWorker()
    }

    private fun setupMediaSessionInterceptor() {
        runCatching {
            mediaSession = MediaSession(activity, "OpenMetaRuSession").apply {
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY or
                                PlaybackState.ACTION_PAUSE or
                                PlaybackState.ACTION_PLAY_PAUSE
                        )
                        .setState(PlaybackState.STATE_PAUSED, 0L, 1f)
                        .build()
                )
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_VOICE_ASSIST,
                                KeyEvent.KEYCODE_ASSIST,
                                KeyEvent.KEYCODE_HEADSETHOOK,
                                KeyEvent.KEYCODE_CALL -> {
                                    onTouchpadLongHold()
                                    return true
                                }
                            }
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })
                isActive = true
            }
        }
    }

    private fun releaseStuckCommandSlotIfIdle() {
        if (GlassesSessionCoordinator.currentSession() == null) {
            GlassesSessionCoordinator.clearForDisconnectedDevice()
        }
    }

    // ================= РУССКИЙ ИНТЕРФЕЙС В СТИЛЕ META VIEW =================

    fun attachRussianMetaUi() {
        val rootContainer = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val dashboardScroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0E0F12"))
            isFillViewport = true
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(42), dp(20), dp(28))
        }

        // 1. Шапка: Ray-Ban | AI + Индикатор подключения
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(activity).apply {
            text = "Ray-Ban | AI"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusBadgeView = TextView(activity).apply {
            text = "○ Подключить"
            setTextColor(Color.parseColor("#F87171"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundedBg("#1C1E24", 20f)
            setOnClickListener {
                releaseStuckCommandSlotIfIdle()
                onScanClick()
            }
        }

        headerRow.addView(titleText)
        headerRow.addView(statusBadgeView)
        content.addView(headerRow)

        // 2. Карточка состояния очков
        val statusCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBg("#181A20", 16f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }

        batteryTextView = TextView(activity).apply {
            text = "Заряд очков: --% • Кадр: 1600×1200 (4:3)"
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        }

        statusSubtitleView = TextView(activity).apply {
            text = "Удержание дужки: ИИ (скажи «Посмотри» или «Что это» для фото)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, 0)
        }

        statusCard.addView(batteryTextView)
        statusCard.addView(statusSubtitleView)
        content.addView(statusCard)

        // 3. Главная синяя кнопка выгрузки медиа
        val btnSync = Button(activity).apply {
            text = "Скинуть и обработать медиа с очков"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isAllCaps = false
            background = roundedBg("#0064E0", 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply { topMargin = dp(16) }
            setOnClickListener {
                releaseStuckCommandSlotIfIdle()
                Toast.makeText(
                    activity,
                    "Выгрузка с очков (${currentRenderMode.title})...",
                    Toast.LENGTH_SHORT
                ).show()
                onSyncClick()
            }
        }
        content.addView(btnSync)

        // 4. Кнопка «Открыть медиа (Скинутые файлы)»
        val btnMediaGallery = Button(activity).apply {
            text = "📂 Открыть медиа (Скинутые файлы)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isAllCaps = false
            background = roundedBg("#1E293B", 14f, strokeColor = "#334155")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply { topMargin = dp(10) }
            setOnClickListener { showBuiltInGalleryDialog() }
        }
        content.addView(btnMediaGallery)

        // 5. Переключатель режимов качества и стабилизации
        val modeHeader = TextView(activity).apply {
            text = "РЕЖИМ КАЧЕСТВА И СТАБИЛИЗАЦИИ:"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(20), 0, dp(8))
        }
        content.addView(modeHeader)

        modeButtons.clear()
        OpenMetaRenderMode.entries.forEach { mode ->
            val btn = Button(activity).apply {
                text = "${mode.title}\n${mode.subtitle}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    currentRenderMode = mode
                    Toast.makeText(activity, "Режим: ${mode.title}", Toast.LENGTH_SHORT).show()
                }
            }
            modeButtons.add(btn)
            content.addView(btn)
        }
        updateModeButtonsUi()

        // 6. Быстрые команды: Фото, Видео, Настройка Gemini
        val actionsHeader = TextView(activity).apply {
            text = "БЫСТРЫЕ КОМАНДЫ:"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(14), 0, dp(8))
        }
        content.addView(actionsHeader)

        val mediaRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val btnPhoto = Button(activity).apply {
            text = "📷 Фото"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = false
            background = roundedBg("#1C1E24", 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) }
            setOnClickListener {
                releaseStuckCommandSlotIfIdle()
                onPhotoClick()
            }
        }

        val btnVideo = Button(activity).apply {
            text = "🎥 Видео старт/стоп"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = false
            background = roundedBg("#1C1E24", 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) }
            setOnClickListener {
                releaseStuckCommandSlotIfIdle()
                onVideoClick()
            }
        }

        mediaRow.addView(btnPhoto)
        mediaRow.addView(btnVideo)
        content.addView(mediaRow)

        geminiBtnView = Button(activity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = false
            background = roundedBg("#181A20", 12f, strokeColor = "#262932")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            )
            setOnClickListener { showGeminiSettingsDialog() }
        }
        updateGeminiButtonText()
        content.addView(geminiBtnView)

        val floatingBackBtn = Button(activity).apply {
            text = "🕶️ Экран Meta RU"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            visibility = View.GONE
            background = roundedBg("#0064E0", 24f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                bottomMargin = dp(90)
                rightMargin = dp(16)
            }
        }

        val bottomServiceRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }

        val btnBind = Button(activity).apply {
            text = "🔍 Привязать очки"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener {
                releaseStuckCommandSlotIfIdle()
                onScanClick()
            }
        }

        val btnToggleRawUi = Button(activity).apply {
            text = "⚙️ Стоковое меню"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener {
                dashboardScroll.visibility = View.GONE
                floatingBackBtn.visibility = View.VISIBLE
            }
        }

        bottomServiceRow.addView(btnBind)
        bottomServiceRow.addView(btnToggleRawUi)
        content.addView(bottomServiceRow)

        floatingBackBtn.setOnClickListener {
            dashboardScroll.visibility = View.VISIBLE
            floatingBackBtn.visibility = View.GONE
        }

        dashboardScroll.addView(content)
        rootContainer.addView(dashboardScroll)
        rootContainer.addView(floatingBackBtn)

        activity.addContentView(
            rootContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun updateGeminiButtonText() {
        val hasKey = geminiApiKey.isNotBlank()
        geminiBtnView?.text = if (hasKey) {
            "🔑 Настройка Gemini ИИ (Ключ активен ✓)"
        } else {
            "🔑 Настройка Gemini ИИ (Ввести ключ)"
        }
    }

    fun updateStatusUi(connected: Boolean, battery: Int?) {
        mainHandler.post {
            val tuneBudsConnected = runCatching { TuneBudsManager.getInstance(activity).isConnected() }.getOrDefault(false)
            val isOnline = connected || tuneBudsConnected || (battery != null && battery > 0)
            statusBadgeView?.text = if (isOnline) "● Подключено" else "○ Подключить"
            statusBadgeView?.setTextColor(
                if (isOnline) Color.parseColor("#4ADE80") else Color.parseColor("#F87171")
            )
            val batStr = battery?.let { "$it%" } ?: "--%"
            batteryTextView?.text = "Заряд очков: $batStr • Кадр: 1600×1200 (4:3)"
        }
    }

    private fun updateModeButtonsUi() {
        val active = currentRenderMode
        modeButtons.forEachIndexed { index, button ->
            val isSelected = (index == active.id)
            button.background = roundedBg(
                if (isSelected) "#1E3A8A" else "#181A20",
                12f,
                strokeColor = if (isSelected) "#3B82F6" else "#262932"
            )
        }
    }

    // ================= ВСТРОЕННАЯ ГАЛЕРЕЯ СКИНУТЫХ ФАЙЛОВ =================

    private data class GalleryItem(
        val id: Long,
        val name: String,
        val uri: Uri,
        val mimeType: String,
        val dateTaken: Long,
        val sizeBytes: Long
    )

    private fun showBuiltInGalleryDialog() {
        val items = querySyncedMediaFiles()
        val pending = pendingRenderCount.get()
        val pendingNote = if (pending > 0) "\n\n⚡ В фоне обрабатывается в 2K: $pending видео..." else ""

        if (items.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("📂 Скинутые медиафайлы")
                .setMessage("В папке DCIM/CyanBridge пока нет скачанных фото или видео.$pendingNote")
                .setPositiveButton("Аудиозаписи") { _, _ ->
                    activity.startActivity(Intent(activity, RecordingsListActivity::class.java))
                }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }

        val sdf = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val labels = items.map { item ->
            val is2K = item.name.startsWith("Meta2K_", ignoreCase = true)
            val icon = when {
                item.mimeType.startsWith("video") && is2K -> "🎬 [2K Стаб]"
                item.mimeType.startsWith("video") -> "🎬 [Исходник]"
                else -> "🖼️ Фото"
            }
            val sizeMb = String.format(Locale.US, "%.1f МБ", item.sizeBytes / (1024f * 1024f))
            val dateStr = sdf.format(Date(item.dateTaken))
            "$icon ${item.name}\n$dateStr • $sizeMb"
        }.toTypedArray()

        val title = if (pending > 0) {
            "📂 Медиа (${items.size}) • ⚡ 2K-рендер: $pending"
        } else {
            "📂 Скинутые файлы (${items.size})"
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(labels) { _, which ->
                val chosen = items[which]
                try {
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(chosen.uri, chosen.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(viewIntent)
                } catch (e: Exception) {
                    Toast.makeText(activity, "Не найдено приложение для открытия файла", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Аудиозаписи") { _, _ ->
                activity.startActivity(Intent(activity, RecordingsListActivity::class.java))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun querySyncedMediaFiles(): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        val resolver = activity.contentResolver

        fun queryUri(contentUri: Uri, defaultMime: String) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            runCatching {
                resolver.query(
                    contentUri,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

                    while (cursor.moveToNext() && result.size < 45) {
                        val relPath = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                        val name = cursor.getString(nameCol).orEmpty()
                        if (relPath.contains("CyanBridge", ignoreCase = true) ||
                            name.startsWith("Meta2K_", ignoreCase = true) ||
                            name.startsWith("Glasses_", ignoreCase = true)
                        ) {
                            val id = cursor.getLong(idCol)
                            val mime = cursor.getString(mimeCol) ?: defaultMime
                            val dateSec = cursor.getLong(dateCol)
                            val size = cursor.getLong(sizeCol)
                            result.add(
                                GalleryItem(
                                    id = id,
                                    name = name,
                                    uri = ContentUris.withAppendedId(contentUri, id),
                                    mimeType = mime,
                                    dateTaken = dateSec * 1000L,
                                    sizeBytes = size
                                )
                            )
                        }
                    }
                }
            }
        }

        queryUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/mp4")
        queryUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg")
        return result.sortedByDescending { it.dateTaken }
    }

    // ================= НАСТРОЙКА GEMINI И HANDS-FREE АССИСТЕНТ С ДУЖКИ =================

    private fun showGeminiSettingsDialog() {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val keyLabel = TextView(activity).apply {
            text = "API-ключ Google Gemini:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val keyInput = EditText(activity).apply {
            hint = "AIzaSy..."
            setText(geminiApiKey)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val proxyLabel = TextView(activity).apply {
            text = "Сервер API (оставь стандартный или укажи прокси для работы без VPN):"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(10), 0, 0)
        }
        val proxyInput = EditText(activity).apply {
            hint = "https://generativelanguage.googleapis.com"
            setText(geminiBaseUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        container.addView(keyLabel)
        container.addView(keyInput)
        container.addView(proxyLabel)
        container.addView(proxyInput)

        AlertDialog.Builder(activity)
            .setTitle("Настройка Gemini ИИ")
            .setMessage("Зажми дужку на очках → после звукового сигнала произнеси вопрос. Если в вопросе есть «Посмотри» или «Что это», очки сами сделают фото и озвучат ответ.")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                geminiApiKey = keyInput.text.toString()
                geminiBaseUrl = proxyInput.text.toString()
                Toast.makeText(activity, "Ключ сохранен!", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Проверить голос") { _, _ ->
                geminiApiKey = keyInput.text.toString()
                geminiBaseUrl = proxyInput.text.toString()
                onTouchpadLongHold()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Вызывается при удержании сенсорной дужки очков (без лишних окон на экране)
    fun onTouchpadLongHold() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < 1200L) return
        lastTriggerTimeMs = now

        mainHandler.post {
            releaseStuckCommandSlotIfIdle()
            if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 701)
                speakRussian("Разреши доступ к микрофону на телефоне")
                return@post
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            tts?.stop()
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                mainHandler.postDelayed({ tone.release() }, 190)
            }

            statusSubtitleView?.text = "🎙️ Слушаю... Скажи вопрос или «Посмотри, что это»"
            statusSubtitleView?.setTextColor(Color.parseColor("#4ADE80"))

            mainHandler.postDelayed({ startHandsFreeListening() }, 220)
        }
    }

    private fun startHandsFreeListening() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val query = matches?.firstOrNull()?.trim().orEmpty()
                        if (query.isNotEmpty()) {
                            statusSubtitleView?.text = "🗣️ «$query»"
                            handleRussianVoiceCommand(query)
                        } else {
                            speakRussian("Не расслышал, повтори")
                        }
                    }

                    override fun onError(error: Int) {
                        Log.w("OpenMetaAI", "SpeechRecognizer error=$error")
                        statusSubtitleView?.text = "Удержание дужки: ИИ («Посмотри» / «Что это»)"
                        statusSubtitleView?.setTextColor(Color.parseColor("#94A3B8"))
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            speakRussian("Не расслышал")
                        }
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1700)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1700)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("OpenMetaAI", "SpeechRecognizer failed", e)
            speakRussian("Не удалось включить распознавание речи")
        }
    }

    fun handleRussianVoiceCommand(query: String) {
        val lower = query.lowercase(Locale("ru", "RU"))
        val needsCamera = visionTriggers.any { lower.contains(it) }

        if (geminiApiKey.isBlank()) {
            speakRussian("Сначала сохрани ключ нейросети в настройках на главном экране")
            return
        }

        if (needsCamera) {
            lastUserVisionQuery = query
            speakRussian("Секунду, смотрю")
            statusSubtitleView?.text = "📸 Снимаю кадр с очков: «$query»..."
            statusSubtitleView?.setTextColor(Color.parseColor("#60A5FA"))

            scope.launch(Dispatchers.IO) {
                val tuneBuds = TuneBudsManager.getInstance(activity)
                if (tuneBuds.isConnected()) {
                    val photoBytes = runCatching { tuneBuds.capturePhotoForAi() }.getOrNull()
                    if (photoBytes != null && photoBytes.isNotEmpty()) {
                        val answer = askGeminiDirect(query, photoBytes)
                        withContext(Dispatchers.Main) {
                            statusSubtitleView?.text = "🤖 $answer"
                            statusSubtitleView?.setTextColor(Color.parseColor("#4ADE80"))
                            speakRussian(answer)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            speakRussian("Не удалось получить кадр с очков, попробуй еще раз")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        releaseStuckCommandSlotIfIdle()
                        onVisionPhotoRequest()
                    }
                }
            }
            return
        }

        statusSubtitleView?.text = "🧠 Думаю: «$query»..."
        scope.launch(Dispatchers.IO) {
            val reply = askGeminiDirect(query, null)
            withContext(Dispatchers.Main) {
                statusSubtitleView?.text = "🤖 $reply"
                statusSubtitleView?.setTextColor(Color.parseColor("#4ADE80"))
                speakRussian(reply)
            }
        }
    }

    fun askGeminiDirect(prompt: String, imageBytes: ByteArray?): String {
        val models = listOf("gemini-2.5-flash", "gemini-2.0-flash")
        var lastError = "ошибка сети"

        for (modelName in models) {
            try {
                val sysPrompt = "Ты голосовой ассистент, встроенный в умные очки пользователя. " +
                    "Отвечай на русском языке, коротко и по делу (1-3 предложения), " +
                    "без списков и звездочек, так как твой ответ сразу читается вслух в наушники очков."

                val parts = JSONArray().apply {
                    put(JSONObject().put("text", "$sysPrompt\n\nВопрос: $prompt"))
                    if (imageBytes != null) {
                        val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        val inlineData = JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", b64)
                        put(JSONObject().put("inline_data", inlineData))
                    }
                }

                val payload = JSONObject().put(
                    "contents",
                    JSONArray().put(JSONObject().put("parts", parts))
                ).toString()

                val endpoint = "$geminiBaseUrl/v1beta/models/$modelName:generateContent?key=$geminiApiKey"
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    return JSONObject(responseText)
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .replace("*", "")
                        .trim()
                } else {
                    val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    conn.disconnect()
                    Log.e("OpenMetaAI", "Gemini ($modelName) HTTP $code: $errBody")
                    if (errBody.contains("User location is not supported", ignoreCase = true) ||
                        errBody.contains("FAILED_PRECONDITION", ignoreCase = true)
                    ) {
                        return "Гугл блокирует запрос с российского айпи без ВПН. Включи ВПН или укажи прокси в настройках."
                    }
                    if (code == 400 && errBody.contains("API_KEY_INVALID", ignoreCase = true)) {
                        return "Указан неверный ключ нейросети Джемини."
                    }
                    lastError = "код $code"
                }
            } catch (e: Exception) {
                Log.e("OpenMetaAI", "Gemini ($modelName) error", e)
                lastError = "нет связи с сервером"
            }
        }
        return "Не удалось связаться с нейросетью, $lastError."
    }

    fun speakRussian(text: String) {
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "OPENMETA_RU_${System.currentTimeMillis()}")
    }

    // ================= ПАЙПЛАЙН ФОТО И ФОНОВАЯ ОЧЕРЕДЬ ВИДЕО 2K + СТАБА =================

    fun processPhotoStream(rawInput: InputStream, fileName: String): File {
        val tempRaw = File(activity.cacheDir, "raw_$fileName")
        val tempOut = File(activity.cacheDir, "out_$fileName")
        FileOutputStream(tempRaw).use { rawInput.copyTo(it) }

        if (currentRenderMode == OpenMetaRenderMode.RAW_ORIGINAL) {
            return tempRaw
        }

        return try {
            val original = BitmapFactory.decodeFile(tempRaw.absolutePath) ?: return tempRaw
            val contrast = 1.12f
            val shift = (-0.5f * contrast + 0.5f) * 255f
            val cm = ColorMatrix(
                floatArrayOf(
                    contrast * 1.04f, 0f, 0f, 0f, shift,
                    0f, contrast * 0.97f, 0f, 0f, shift,
                    0f, 0f, contrast * 1.02f, 0f, shift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(ColorMatrix().apply { setSaturation(1.22f) })

            val enhanced = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
            Canvas(enhanced).drawBitmap(original, 0f, 0f, paint)
            FileOutputStream(tempOut).use { enhanced.compress(Bitmap.CompressFormat.JPEG, 96, it) }

            original.recycle()
            enhanced.recycle()
            tempRaw.delete()
            tempOut
        } catch (e: Exception) {
            Log.e("OpenMetaPipeline", "Photo enhance failed, using raw", e)
            tempRaw
        }
    }

    // Не задерживает Wi-Fi синхронизацию очков: мгновенно отдает сырой файл, а 2K-рендер ставит в фоновую очередь
    suspend fun processVideoFile(rawVideoFile: File): File {
        val mode = currentRenderMode
        if (mode == OpenMetaRenderMode.RAW_ORIGINAL) {
            return rawVideoFile
        }

        val queuedCopy = File(activity.cacheDir, "queue_${System.currentTimeMillis()}_${rawVideoFile.name}")
        rawVideoFile.copyTo(queuedCopy, overwrite = true)
        val cleanName = rawVideoFile.name.substringAfterLast('_')
        val count = pendingRenderCount.incrementAndGet()
        updateRenderQueueUi(count, cleanName)
        renderChannel.trySend(QueuedVideoJob(queuedCopy, cleanName, mode))

        return rawVideoFile
    }

    private fun updateRenderQueueUi(remaining: Int, currentName: String?) {
        mainHandler.post {
            if (remaining > 0) {
                statusSubtitleView?.text = "⚡ Фоновый 2K-рендер и стаба (осталось: $remaining) • ${currentName.orEmpty()}"
                statusSubtitleView?.setTextColor(Color.parseColor("#60A5FA"))
            } else {
                statusSubtitleView?.text = "✅ 2K-рендер завершен! Ролики лежат в «Открыть медиа»"
                statusSubtitleView?.setTextColor(Color.parseColor("#4ADE80"))
            }
        }
    }

    private fun startBackgroundRenderWorker() {
        scope.launch(Dispatchers.IO) {
            for (job in renderChannel) {
                updateRenderQueueUi(pendingRenderCount.get(), job.displayName)
                val renderedFile = renderVideoInternal(job.rawFile, job.mode)
                if (renderedFile != job.rawFile && renderedFile.exists()) {
                    saveRenderedVideoToMediaStore(renderedFile, "Meta2K_${job.displayName}")
                    renderedFile.delete()
                }
                job.rawFile.delete()
                val left = pendingRenderCount.decrementAndGet().coerceAtLeast(0)
                updateRenderQueueUi(left, null)
            }
        }
    }

    private suspend fun renderVideoInternal(rawVideoFile: File, mode: OpenMetaRenderMode): File {
        val outFile = File(activity.cacheDir, "meta2k_${System.currentTimeMillis()}_${rawVideoFile.name}")
        return try {
            val lockHorizon = (mode == OpenMetaRenderMode.HORIZON_LOCK_2K)
            val radius = if (lockHorizon) 5 else 3
            val trajectory = withContext(Dispatchers.Default) {
                HorizonStabilizerEffect.analyzeWithHorizonLock(rawVideoFile, radius)
            }

            val deferred = CompletableDeferred<File>()
            withContext(Dispatchers.Main) {
                val effects = listOf<Effect>(
                    HorizonStabilizerEffect(
                        trajectory = trajectory,
                        stepUs = 120_000L,
                        baseZoom = 1.08f,
                        lockHorizon = lockHorizon
                    ),
                    Presentation.createForWidthAndHeight(
                        2048,
                        1536,
                        Presentation.LAYOUT_SCALE_TO_FIT
                    ),
                    MetaCinemaGlEffect()
                )

                val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(rawVideoFile)))
                    .setEffects(Effects(emptyList(), effects))
                    .build()

                val encoderSettings = VideoEncoderSettings.Builder()
                    .setBitrate(18_000_000)
                    .build()

                val encoderFactory = DefaultEncoderFactory.Builder(activity)
                    .setRequestedVideoEncoderSettings(encoderSettings)
                    .build()

                val transformer = Transformer.Builder(activity)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            deferred.complete(outFile)
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            Log.e("OpenMetaPipeline", "Media3 render error", exception)
                            outFile.delete()
                            deferred.complete(rawVideoFile)
                        }
                    })
                    .build()

                transformer.start(editedItem, outFile.absolutePath)
            }
            deferred.await()
        } catch (e: Exception) {
            Log.e("OpenMetaPipeline", "Video pipeline exception", e)
            rawVideoFile
        }
    }

    private fun saveRenderedVideoToMediaStore(videoFile: File, displayName: String) {
        try {
            val nowMs = System.currentTimeMillis()
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
                put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, nowMs / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, SyncedMediaFolder.relativePath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri, "w")?.use { out ->
                videoFile.inputStream().use { it.copyTo(out) }
                out.flush()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
            Log.e("OpenMetaPipeline", "Failed to save 2K video", e)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            activity.resources.displayMetrics
        ).toInt()

    private fun roundedBg(
        fillHex: String,
        radiusDp: Float,
        strokeColor: String? = null
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(Color.parseColor(fillHex))
        if (strokeColor != null) {
            setStroke(dp(1), Color.parseColor(strokeColor))
        }
    }
}

@OptIn(UnstableApi::class)
class HorizonStabilizerEffect(
    private val trajectory: List<FramePose>,
    private val stepUs: Long = 120_000L,
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

        val exactIndex = presentationTimeUs.toFloat() / stepUs.toFloat()
        val i0 = exactIndex.toInt().coerceIn(0, trajectory.lastIndex)
        val i1 = (i0 + 1).coerceIn(0, trajectory.lastIndex)
        val frac = (exactIndex - i0).coerceIn(0f, 1f)

        val p0 = trajectory[i0]
        val p1 = trajectory[i1]

        val shiftX = p0.dx + (p1.dx - p0.dx) * frac
        val shiftY = p0.dy + (p1.dy - p0.dy) * frac
        val angleDeg = if (lockHorizon) {
            (p0.rollDeg + (p1.rollDeg - p0.rollDeg) * frac).coerceIn(-14f, 14f)
        } else 0f

        val rad = Math.toRadians(abs(angleDeg).toDouble())
        val rotZoom = (cos(rad) + (4.0 / 3.0) * sin(rad)).toFloat()
        val totalZoom = max(baseZoom, rotZoom * 1.04f)

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
        fun analyzeWithHorizonLock(videoFile: File, radius: Int): List<FramePose> {
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
                val stepMs = 120L

                while (timeMs < durationMs) {
                    val bmp = retriever.getFrameAtTime(
                        timeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    ) ?: retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)

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
                            val dAngle = Math.toDegrees(atan2(deltaY.toDouble(), 40.0)).toFloat()
                            cumAngle = (cumAngle + dAngle) * 0.96f
                        }
                        prevLuma = currLuma
                    }
                    rawPoses.add(FramePose(cumX, cumY, cumAngle))
                    timeMs += stepMs
                }
            } catch (_: Exception) {
                return emptyList()
            } finally {
                retriever.release()
            }

            return rawPoses.mapIndexed { i, cur ->
                var sx = 0f
                var sy = 0f
                var sa = 0f
                var count = 0
                for (j in max(0, i - radius)..min(rawPoses.lastIndex, i + radius)) {
                    sx += rawPoses[j].dx
                    sy += rawPoses[j].dy
                    sa += rawPoses[j].rollDeg
                    count++
                }
                FramePose(
                    dx = (sx / count) - cur.dx,
                    dy = (sy / count) - cur.dy,
                    rollDeg = ((sa / count) * 0.3f) - cur.rollDeg
                )
            }
        }

        private fun extractLuma(bmp: Bitmap): IntArray {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            return IntArray(w * h) { idx ->
                val p = px[idx]
                (((p shr 16 and 0xFF) * 77) + ((p shr 8 and 0xFF) * 150) + ((p and 0xFF) * 29)) shr 8
            }
        }

        private fun matchZone(
            prev: IntArray,
            curr: IntArray,
            w: Int,
            h: Int,
            startX: Int,
            endX: Int
        ): Pair<Float, Float> {
            var bestDx = 0
            var bestDy = 0
            var minSad = Int.MAX_VALUE
            for (dy in -6..6) {
                for (dx in -6..6) {
                    var sad = 0
                    for (y in 18 until h - 18 step 2) {
                        for (x in startX until endX step 2) {
                            sad += abs(prev[y * w + x] - curr[(y + dy) * w + (x + dx)])
                        }
                    }
                    if (sad < minSad) {
                        minSad = sad
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
            return Pair((bestDx.toFloat() / w) * 2f, -(bestDy.toFloat() / h) * 2f)
        }
    }
}

@OptIn(UnstableApi::class)
class MetaCinemaGlEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MetaCinemaShaderProgram(useHdr)
}

@OptIn(UnstableApi::class)
class MetaCinemaShaderProgram(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
        setBufferAttribute(
            "aFramePosition",
            floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 0f, 1f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 0f, 1f
            ),
            4
        )
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;

            void main() {
                vec2 texel = vec2(1.0 / 2048.0, 1.0 / 1536.0);
                vec3 c = texture2D(uTexSampler, vTexSamplingCoord).rgb;
                vec3 n = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -texel.y)).rgb;
                vec3 s = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0,  texel.y)).rgb;
                vec3 w = texture2D(uTexSampler, vTexSamplingCoord + vec2(-texel.x, 0.0)).rgb;
                vec3 e = texture2D(uTexSampler, vTexSamplingCoord + vec2( texel.x, 0.0)).rgb;

                vec3 minRGB = min(min(min(n, s), min(w, e)), c);
                vec3 maxRGB = max(max(max(n, s), max(w, e)), c);
                vec3 amp = clamp(min(minRGB, 1.0 - maxRGB) / max(maxRGB, vec3(0.0001)), 0.0, 1.0);
                amp = sqrt(amp);
                vec3 weight = amp * (-0.18);
                vec3 sharpened = clamp((c + (n + s + w + e) * weight) / (1.0 + 4.0 * weight), 0.0, 1.0);

                vec3 graded = vec3(sharpened.r * 1.04, sharpened.g * 0.97, sharpened.b * 1.02);
                graded = clamp(graded, 0.0, 1.0);
                graded = mix(graded, smoothstep(0.03, 0.97, graded), 0.35);

                float luma = dot(graded, vec3(0.299, 0.587, 0.114));
                float sat = max(graded.r, max(graded.g, graded.b)) - min(graded.r, min(graded.g, graded.b));
                graded = mix(vec3(luma), graded, 1.0 + (0.28 * (1.0 - sat)));

                gl_FragColor = vec4(clamp(graded, 0.0, 1.0), 1.0);
            }
        """
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        glProgram.delete()
    }
}
