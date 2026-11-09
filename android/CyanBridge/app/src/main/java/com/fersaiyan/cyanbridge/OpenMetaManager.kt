package com.fersaiyan.cyanbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import android.net.Uri
import android.opengl.GLES20
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
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
        "Без обработки: прямое скачивание с очков как есть"
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
    private var statusBadgeView: TextView? = null
    private var batteryTextView: TextView? = null
    private var modeButtons = mutableListOf<Button>()

    private val visionTriggers = listOf(
        "посмотри", "смотри", "глянь", "взгляни", "видишь",
        "что это", "что тут", "что здесь", "что за", "что перед",
        "прочитай", "переведи", "оцени", "сфоткай", "на фото", "покажи"
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
        }

    init {
        tts = TextToSpeech(activity.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setSpeechRate(1.06f)
            }
        }
    }

    // Отрисовка русского интерфейса в стиле Meta View поверх стандартного экрана
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

        // 1. Шапка: Название + Статус подключения
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
            text = "● Готов к работе"
            setTextColor(Color.parseColor("#4ADE80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundedBg("#1C1E24", 20f)
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
            text = "Очки подключены • Исходник: 1600×1200 (4:3)"
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val hintText = TextView(activity).apply {
            text = "Кнопка: Фото / Видео • Удержание дужки: Русский ИИ («Посмотри...»)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, 0)
        }

        statusCard.addView(batteryTextView)
        statusCard.addView(hintText)
        content.addView(statusCard)

        // 3. Главная синяя кнопка: Импорт и обработка медиа
        val btnSync = Button(activity).apply {
            text = "Скинуть и обработать медиа с очков"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isAllCaps = false
            background = roundedBg("#0064E0", 14f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            ).apply { topMargin = dp(18) }
            setOnClickListener {
                Toast.makeText(
                    activity,
                    "Запуск выгрузки (${currentRenderMode.title})...",
                    Toast.LENGTH_SHORT
                ).show()
                onSyncClick()
            }
        }
        content.addView(btnSync)

        // 4. Заголовок выбора режима обработки и стабилизации
        val modeHeader = TextView(activity).apply {
            text = "РЕЖИМ КАЧЕСТВА И СТАБИЛИЗАЦИИ:"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(22), 0, dp(8))
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
                    Toast.makeText(activity, "Выбран: ${mode.title}", Toast.LENGTH_SHORT).show()
                }
            }
            modeButtons.add(btn)
            content.addView(btn)
        }
        updateModeButtonsUi()

        // 5. Быстрые кнопки управления (ИИ, Фото, Видео)
        val actionsHeader = TextView(activity).apply {
            text = "БЫСТРЫЕ КОМАНДЫ:"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(14), 0, dp(8))
        }
        content.addView(actionsHeader)

        val btnAskAi = Button(activity).apply {
            text = "🎙️ Спросить русский ИИ (или скажи «Посмотри»)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = false
            background = roundedBg("#1E293B", 12f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { onTouchpadLongHold() }
        }
        content.addView(btnAskAi)

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
            isAllCaps = false
            background = roundedBg("#1C1E24", 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) }
            setOnClickListener { onPhotoClick() }
        }

        val btnVideo = Button(activity).apply {
            text = "🎥 Видео старт/стоп"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundedBg("#1C1E24", 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) }
            setOnClickListener { onVideoClick() }
        }

        mediaRow.addView(btnPhoto)
        mediaRow.addView(btnVideo)
        content.addView(mediaRow)

        // 6. Нижние сервисные кнопки: Поиск очков, Ключ Gemini и Инженерное меню
        val serviceRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        val btnBind = Button(activity).apply {
            text = "🔍 Привязать очки"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            background = roundedBg("#181A20", 10f)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) }
            setOnClickListener { onScanClick() }
        }

        val btnApiKey = Button(activity).apply {
            text = "🔑 Ключ Gemini ИИ"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            background = roundedBg("#181A20", 10f)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) }
            setOnClickListener { showApiKeyDialog() }
        }

        serviceRow.addView(btnBind)
        serviceRow.addView(btnApiKey)
        content.addView(serviceRow)

        // Плавающая кнопка возврата в русский интерфейс, если открыто инженерное меню
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

        val btnToggleRawUi = Button(activity).apply {
            text = "⚙️ Открыть инженерное меню CyanBridge"
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(12) }
            setOnClickListener {
                dashboardScroll.visibility = View.GONE
                floatingBackBtn.visibility = View.VISIBLE
            }
        }
        content.addView(btnToggleRawUi)

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

    fun updateStatusUi(connected: Boolean, battery: Int?) {
        mainHandler.post {
            statusBadgeView?.text = if (connected) "● Подключено" else "○ Отключено"
            statusBadgeView?.setTextColor(
                if (connected) Color.parseColor("#4ADE80") else Color.parseColor("#F87171")
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

    private fun showApiKeyDialog() {
        val input = EditText(activity).apply {
            hint = "Вставь API-ключ Gemini (AIzaSy...)"
            setText(geminiApiKey)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(activity)
            .setTitle("Ключ нейросети Gemini")
            .setMessage("Ключ сохраняется локально на телефоне для голосового ассистента и функции «Посмотри».")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                geminiApiKey = input.text.toString()
                Toast.makeText(activity, "Ключ сохранен!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ================= ГОЛОСОВОЙ АССИСТЕНТ И «ПОСМОТРИ» =================

    fun onTouchpadLongHold() {
        mainHandler.post {
            speakRussian("Слушаю")
            mainHandler.postDelayed({ startRussianListening() }, 450)
        }
    }

    private fun startRussianListening() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val query = matches?.firstOrNull()?.trim().orEmpty()
                        if (query.isNotEmpty()) {
                            handleRussianVoiceCommand(query)
                        } else {
                            speakRussian("Не расслышал, повтори")
                        }
                    }

                    override fun onError(error: Int) {
                        Log.w("OpenMetaAI", "Speech error=$error")
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
        } catch (e: Exception) {
            Log.e("OpenMetaAI", "Failed to start Russian recognition", e)
        }
    }

    private fun handleRussianVoiceCommand(query: String) {
        val lower = query.lowercase(Locale("ru", "RU"))
        val needsCamera = visionTriggers.any { lower.contains(it) }

        if (needsCamera) {
            speakRussian("Секунду, смотрю")
            // Если пользователь сказал «посмотри», запускаем штатный захват кадра с очков в ИИ
            onVisionPhotoRequest()
            return
        }

        if (geminiApiKey.isBlank()) {
            speakRussian("Введи ключ нейросети в настройках на главном экране")
            return
        }

        scope.launch(Dispatchers.IO) {
            val reply = askGeminiDirect(query, null)
            withContext(Dispatchers.Main) {
                speakRussian(reply)
            }
        }
    }

    private fun askGeminiDirect(prompt: String, imageBytes: ByteArray?): String {
        return try {
            val sysPrompt = "Ты голосовой ассистент в умных очках пользователя. " +
                "Отвечай на русском языке, кратко и по делу (максимум 2-3 предложения), " +
                "без списков и символов разметки, чтобы текст легко читался вслух."

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

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiApiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            JSONObject(responseText)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .replace("*", "")
                .trim()
        } catch (e: Exception) {
            Log.e("OpenMetaAI", "Gemini request failed", e)
            "Не удалось получить ответ от нейросети."
        }
    }

    fun speakRussian(text: String) {
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "OPENMETA_RU_${System.currentTimeMillis()}")
    }

    // ================= ПАЙПЛАЙН ФОТО И ВИДЕО (4:3 2K + СТАБА + ГОРИЗОНТ) =================

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

    suspend fun processVideoFile(rawVideoFile: File): File {
        val mode = currentRenderMode
        if (mode == OpenMetaRenderMode.RAW_ORIGINAL) {
            return rawVideoFile
        }

        val outFile = File(activity.cacheDir, "meta2k_${System.currentTimeMillis()}_${rawVideoFile.name}")
        return try {
            val lockHorizon = (mode == OpenMetaRenderMode.HORIZON_LOCK_2K)
            val radius = if (lockHorizon) 12 else 8
            val trajectory = withContext(Dispatchers.Default) {
                HorizonStabilizerEffect.analyzeWithHorizonLock(rawVideoFile, radius)
            }

            val deferred = CompletableDeferred<File>()
            withContext(Dispatchers.Main) {
                val effects = listOf<Effect>(
                    HorizonStabilizerEffect(
                        trajectory = trajectory,
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
                            rawVideoFile.delete()
                            deferred.complete(outFile)
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            Log.e("OpenMetaPipeline", "Media3 render error, falling back to raw", exception)
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
        val i0 = exactIndex.toInt().coerceIn(0, trajectory.lastIndex)
        val i1 = (i0 + 1).coerceIn(0, trajectory.lastIndex)
        val frac = exactIndex - i0

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
                            val dAngle = Math.toDegrees(atan2(deltaY.toDouble(), 40.0)).toFloat()
                            cumAngle = (cumAngle + dAngle) * 0.97f
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
        setBufferAttribute("aFramePosition", GlProgram.getNormalizedCoordinateBounds(), 4)
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
                graded = mix(vec3
