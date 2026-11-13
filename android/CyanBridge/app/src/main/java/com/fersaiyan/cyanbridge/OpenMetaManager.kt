package com.fersaiyan.cyanbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
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
import java.text.SimpleDateFormat
import java.util.Date
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
        "Оригинал 1600×1200",
        "Без обработки, прямое сохранение в Галерею"
    ),
    META_FAST_2K(
        1,
        "Meta 2K (Стабилизация + Резкость)",
        "Апскейл 2048×1536, шейдер резкости CAS и коррекция цвета"
    ),
    HORIZON_LOCK_2K(
        2,
        "Горизонт Lock 2K (Удержание горизонта)",
        "Выравнивание наклона головы, полная стабилизация и апскейл 2K"
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
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val fontMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val fontBold = Typeface.create("sans-serif", Typeface.BOLD)

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaSession: MediaSession? = null
    private var lastTriggerTimeMs = 0L

    private var rootMountContainer: FrameLayout? = null
    private var mainWrapperLayout: LinearLayout? = null
    private var floatingBackToMetaBtn: TextView? = null
    private var activeTabIndex: Int = 0

    private var homeTabContainer: ScrollView? = null
    private var mediaTabContainer: ScrollView? = null
    private var bottomTabHomeLabel: TextView? = null
    private var bottomTabMediaLabel: TextView? = null
    private var bottomTabHomeIcon: VectorIconView? = null
    private var bottomTabMediaIcon: VectorIconView? = null

    private var batteryTextView: TextView? = null
    private var batteryIconView: BatteryVectorView? = null
    private var statusHintView: TextView? = null
    private var homeRecentGridContainer: LinearLayout? = null

    private var recordingCardLayout: LinearLayout? = null
    private var recordingTimerLabel: TextView? = null
    private var recordingProgressBar: ProgressBar? = null
    private var pillVideoRecordBtn: TextView? = null
    private var pillVideoLimitBtn: TextView? = null
    private var pillCarAudioBtn: TextView? = null

    private var transferBannerCard: LinearLayout? = null
    private var transferStatusTitle: TextView? = null
    private var transferStatusSubtitle: TextView? = null
    private var transferProgressBar: ProgressBar? = null
    private var mediaFullGridContainer: LinearLayout? = null

    private var activeVideoTimer: CountDownTimer? = null
    private var isRecordingVideo = false
    private var isRenderingVideo = false

    private var lastKnownConnected = false
    private var lastKnownBattery: Int? = null

    var lastUserVisionQuery: String = "Коротко опиши, что перед пользователем на этом кадре."

    private val visionTriggers = listOf(
        "посмотри", "смотри", "глянь", "взгляни", "видишь",
        "что это", "сто это", "че это", "чё это", "что тут",
        "что здесь", "что за", "что перед", "кто это",
        "прочитай", "переведи", "оцени", "сфоткай", "сфотай", "на фото", "покажи"
    )

    var isDarkMode: Boolean
        get() = prefs.getBoolean("meta_dark_mode", false)
        set(value) {
            prefs.edit().putBoolean("meta_dark_mode", value).apply()
            rebuildMetaUi()
        }

    var selectedVideoDurationSec: Int
        get() = prefs.getInt("video_duration_sec", 60)
        set(value) {
            prefs.edit().putInt("video_duration_sec", value).apply()
            updateDurationPillUi()
        }

    var isGlassesMediaAudioEnabled: Boolean
        get() = prefs.getBoolean("glasses_media_audio_enabled", true)
        set(value) {
            prefs.edit().putBoolean("glasses_media_audio_enabled", value).apply()
            updateAudioPillUi()
            applyGlassesA2dpRoute(value)
        }

    var geminiApiKey: String
        get() = prefs.getString("gemini_api_key", "") ?: ""
        set(value) {
            prefs.edit().putString("gemini_api_key", value.trim()).apply()
        }

    var geminiBaseUrl: String
        get() = prefs.getString("gemini_base_url", "https://generativelanguage.googleapis.com")
            ?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: "https://generativelanguage.googleapis.com"
        set(value) {
            val clean = value.trim().trimEnd('/').ifEmpty { "https://generativelanguage.googleapis.com" }
            prefs.edit().putString("gemini_base_url", clean).apply()
        }

    var currentRenderMode: OpenMetaRenderMode
        get() = OpenMetaRenderMode.fromId(prefs.getInt("render_mode", OpenMetaRenderMode.META_FAST_2K.id))
        set(value) {
            prefs.edit().putInt("render_mode", value.id).apply()
        }

    private val stagingDir: File
        get() = File(activity.filesDir, "openmeta_synced").apply { if (!exists()) mkdirs() }

    init {
        tts = TextToSpeech(activity.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                tts?.setSpeechRate(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (!isGlassesMediaAudioEnabled) stopGlassesVoiceCallChannel()
                    }
                    override fun onError(utteranceId: String?) {
                        if (!isGlassesMediaAudioEnabled) stopGlassesVoiceCallChannel()
                    }
                })
            }
        }
        setupMediaSessionInterceptor()
        if (!isGlassesMediaAudioEnabled) {
            mainHandler.postDelayed({ applyGlassesA2dpRoute(false) }, 1200)
        }
    }

    private fun setupMediaSessionInterceptor() {
        runCatching {
            mediaSession = MediaSession(activity, "OpenMetaSession").apply {
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

    // ================= АВТОМОБИЛЬНЫЙ РЕЖИМ (УПРАВЛЕНИЕ ПРОФИЛЕМ A2DP) =================

    @SuppressLint("MissingPermission")
    private fun applyGlassesA2dpRoute(enableMusicInGlasses: Boolean) {
        runCatching {
            val btManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return

            adapter.getProfileProxy(
                activity.applicationContext,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        runCatching {
                            val connectedList = proxy.connectedDevices.orEmpty()
                            val bondedList = adapter.bondedDevices.orEmpty().toList()
                            val glassesDevice = findGlassesBluetoothDevice(connectedList)
                                ?: findGlassesBluetoothDevice(bondedList)

                            if (!enableMusicInGlasses) {
                                val otherAudioDevice = connectedList.firstOrNull { it != glassesDevice }
                                if (otherAudioDevice != null) {
                                    invokeSetActiveA2dpDevice(adapter, proxy, otherAudioDevice)
                                } else {
                                    invokeRemoveActiveA2dpDevice(adapter, proxy)
                                }
                                if (glassesDevice != null) {
                                    runCatching {
                                        val disconnectMethod = proxy.javaClass.getMethod(
                                            "disconnect",
                                            BluetoothDevice::class.java
                                        )
                                        disconnectMethod.isAccessible = true
                                        disconnectMethod.invoke(proxy, glassesDevice)
                                    }
                                }
                            } else {
                                if (glassesDevice != null) {
                                    runCatching {
                                        val connectMethod = proxy.javaClass.getMethod(
                                            "connect",
                                            BluetoothDevice::class.java
                                        )
                                        connectMethod.isAccessible = true
                                        connectMethod.invoke(proxy, glassesDevice)
                                    }
                                    invokeSetActiveA2dpDevice(adapter, proxy, glassesDevice)
                                }
                            }
                        }
                        adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    }

                    override fun onServiceDisconnected(profile: Int) {}
                },
                BluetoothProfile.A2DP
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun findGlassesBluetoothDevice(devices: List<BluetoothDevice>): BluetoothDevice? {
        if (devices.isEmpty()) return null
        val keywords = listOf(
            "tunebuds", "buds", "cyan", "heycyan", "glasses", "meta",
            "ray-ban", "rayban", "g10", "m01", "q1", "aibuds", "smart"
        )
        return devices.firstOrNull { dev ->
            val name = runCatching { dev.name.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
            keywords.any { name.contains(it) }
        } ?: devices.firstOrNull()
    }

    private fun invokeSetActiveA2dpDevice(
        adapter: BluetoothAdapter,
        a2dpProxy: BluetoothProfile,
        device: BluetoothDevice?
    ) {
        runCatching {
            val m = a2dpProxy.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
            m.isAccessible = true
            m.invoke(a2dpProxy, device)
            return
        }
        runCatching {
            val m = adapter.javaClass.getMethod(
                "setActiveDevice",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            if (device != null) m.invoke(adapter, device, 0)
        }
    }

    private fun invokeRemoveActiveA2dpDevice(
        adapter: BluetoothAdapter,
        a2dpProxy: BluetoothProfile
    ) {
        runCatching {
            val m = a2dpProxy.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
            m.isAccessible = true
            m.invoke(a2dpProxy, null)
            return
        }
        runCatching {
            val m = adapter.javaClass.getMethod("removeActiveDevice", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(adapter, 0)
        }
    }

    private fun startGlassesVoiceCallChannel() {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val btVoiceDevice = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (btVoiceDevice != null) {
                    audioManager.setCommunicationDevice(btVoiceDevice)
                    return
                }
            }
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun stopGlassesVoiceCallChannel() {
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

    // ================= ИНИЦИАЛИЗАЦИЯ И ПОСТРОЕНИЕ ИНТЕРФЕЙСА OPENMETA =================

    fun attachRussianMetaUi() {
        val mount = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootMountContainer = mount
        activity.addContentView(
            mount,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        rebuildMetaUi()

        if (!prefs.getBoolean("onboarding_completed", false)) {
            mainHandler.postDelayed({ showFirstLaunchOnboardingDialog() }, 350)
        }
    }

    private fun rebuildMetaUi() {
        val mount = rootMountContainer ?: return
        mount.removeAllViews()

        val dark = isDarkMode
        val bgPage = if (dark) "#0B0D12" else "#F4F6F9"
        val bgCard = if (dark) "#151821" else "#FFFFFF"
        val bgPill = if (dark) "#1C202B" else "#FFFFFF"
        val textPrimary = if (dark) "#F3F4F6" else "#111827"
        val textSecondary = if (dark) "#9CA3AF" else "#6B7280"
        val strokeSubtle = if (dark) "#262B38" else "#E5E7EB"
        val metaBlue = "#0064E0"

        val mainWrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor(bgPage))
        }
        mainWrapperLayout = mainWrapper

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(42), dp(20), dp(12))
        }

        val headerTitle = TextView(activity).apply {
            text = "OpenMeta"
            setTextColor(Color.parseColor(textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
            typeface = fontBold
            letterSpacing = -0.02f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnAddDevice = FrameLayout(activity).apply {
            background = circleBg(bgCard, strokeSubtle)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) }
            addView(
                VectorIconView(activity, VectorIconView.TYPE_PLUS, Color.parseColor(textPrimary)).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                }
            )
            setOnClickListener {
                releaseStuckCommandSlotIfIdle()
                onScanClick()
            }
        }

        val btnThemeToggle = FrameLayout(activity).apply {
            background = circleBg(bgCard, strokeSubtle)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            addView(
                VectorIconView(
                    activity,
                    if (dark) VectorIconView.TYPE_SUN else VectorIconView.TYPE_MOON,
                    Color.parseColor(textPrimary)
                ).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                }
            )
            setOnClickListener { isDarkMode = !dark }
        }

        topBar.addView(headerTitle)
        topBar.addView(btnAddDevice)
        topBar.addView(btnThemeToggle)
        mainWrapper.addView(topBar)

        val pagesFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        homeTabContainer = buildHomeTab(dark, bgCard, bgPill, textPrimary, textSecondary, strokeSubtle, metaBlue)
        mediaTabContainer = buildMediaTab(dark, bgCard, textPrimary, textSecondary, strokeSubtle, metaBlue)

        pagesFrame.addView(homeTabContainer)
        pagesFrame.addView(mediaTabContainer)
        mainWrapper.addView(pagesFrame)

        val bottomNav = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(bgCard, 0f, strokeSubtle)
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }

        val tabHomeBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { switchTab(0) }
        }
        bottomTabHomeIcon = VectorIconView(activity, VectorIconView.TYPE_GLASSES, Color.parseColor(metaBlue)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(20))
        }
        bottomTabHomeLabel = TextView(activity).apply {
            text = "Устройства"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = fontMedium
            setPadding(0, dp(3), 0, 0)
        }
        tabHomeBox.addView(bottomTabHomeIcon)
        tabHomeBox.addView(bottomTabHomeLabel)

        val tabMediaBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { switchTab(1) }
        }
        bottomTabMediaIcon = VectorIconView(activity, VectorIconView.TYPE_GRID, Color.parseColor(textSecondary)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        }
        bottomTabMediaLabel = TextView(activity).apply {
            text = "Медиа"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = fontMedium
            setPadding(0, dp(3), 0, 0)
        }
        tabMediaBox.addView(bottomTabMediaIcon)
        tabMediaBox.addView(bottomTabMediaLabel)

        bottomNav.addView(tabHomeBox)
        bottomNav.addView(tabMediaBox)
        mainWrapper.addView(bottomNav)

        val backBtn = TextView(activity).apply {
            text = "Вернуться в OpenMeta"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = fontMedium
            visibility = View.GONE
            background = roundedBg(metaBlue, 22f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                bottomMargin = dp(86)
                rightMargin = dp(18)
            }
            setOnClickListener {
                mainWrapper.visibility = View.VISIBLE
                visibility = View.GONE
                refreshAllMediaGrids()
            }
        }
        floatingBackToMetaBtn = backBtn

        mount.addView(mainWrapper)
        mount.addView(backBtn)

        switchTab(activeTabIndex)
        updateStatusUi(lastKnownConnected, lastKnownBattery)
        refreshAllMediaGrids()
    }

    private fun switchTab(index: Int) {
        activeTabIndex = index
        val dark = isDarkMode
        val activeColor = Color.parseColor("#0064E0")
        val inactiveColor = Color.parseColor(if (dark) "#9CA3AF" else "#6B7280")

        homeTabContainer?.visibility = if (index == 0) View.VISIBLE else View.GONE
        mediaTabContainer?.visibility = if (index == 1) View.VISIBLE else View.GONE

        bottomTabHomeLabel?.setTextColor(if (index == 0) activeColor else inactiveColor)
        bottomTabMediaLabel?.setTextColor(if (index == 1) activeColor else inactiveColor)
        bottomTabHomeIcon?.setTint(if (index == 0) activeColor else inactiveColor)
        bottomTabMediaIcon?.setTint(if (index == 1) activeColor else inactiveColor)

        if (index == 1) {
            refreshAllMediaGrids()
        }
    }

    // ================= ВКЛАДКА 1: УСТРОЙСТВА =================

    private fun buildHomeTab(
        dark: Boolean,
        bgCard: String,
        bgPill: String,
        textPrimary: String,
        textSecondary: String,
        strokeSubtle: String,
        metaBlue: String
    ): ScrollView {
        val scroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(24))
        }

        val heroCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = roundedBg(bgCard, 24f, strokeSubtle)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val heroTopRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        val heroLeftCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val brandRow = TextView(activity).apply {
            text = "Ray-Ban  |  Meta"
            setTextColor(Color.parseColor(textSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = fontMedium
            letterSpacing = 0.03f
        }

        val modelTitle = TextView(activity).apply {
            text = "Wayfarer"
            setTextColor(Color.parseColor(textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = fontBold
            setPadding(0, dp(2), 0, dp(6))
        }

        val batteryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        batteryIconView = BatteryVectorView(activity, Color.parseColor(textPrimary)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(12)).apply { rightMargin = dp(6) }
        }

        batteryTextView = TextView(activity).apply {
            text = "Не подключено"
            setTextColor(Color.parseColor(textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = fontMedium
        }

        batteryRow.addView(batteryIconView)
        batteryRow.addView(batteryTextView)

        heroLeftCol.addView(brandRow)
        heroLeftCol.addView(modelTitle)
        heroLeftCol.addView(batteryRow)

        val gearBtn = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            addView(
                VectorIconView(activity, VectorIconView.TYPE_GEAR, Color.parseColor(textSecondary)).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                }
            )
            setOnClickListener { showUnifiedSettingsDialog() }
        }

        heroTopRow.addView(heroLeftCol)
        heroTopRow.addView(gearBtn)
        heroCard.addView(heroTopRow)

        val glassesDrawing = WayfarerGlassesView(activity, dark).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(118)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(6)
            }
            setOnClickListener { startSyncAndOpenMediaTab() }
        }
        heroCard.addView(glassesDrawing)

        recordingCardLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isRecordingVideo) View.VISIBLE else View.GONE
            background = roundedBg(if (dark) "#2A1519" else "#FEF2F2", 16f, "#EF4444")
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        val recHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        recordingTimerLabel = TextView(activity).apply {
            text = "Идет запись • Осталось 01:00"
            setTextColor(Color.parseColor("#EF4444"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = fontBold
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val stopRecBtn = TextView(activity).apply {
            text = "Остановить"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = fontMedium
            background = roundedBg("#EF4444", 10f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { stopVideoRecordingWithTimer() }
        }

        recHeaderRow.addView(recordingTimerLabel)
        recHeaderRow.addView(stopRecBtn)

        recordingProgressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 1000
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply { topMargin = dp(8) }
        }

        recordingCardLayout?.addView(recHeaderRow)
        recordingCardLayout?.addView(recordingProgressBar)
        heroCard.addView(recordingCardLayout)

        statusHintView = TextView(activity).apply {
            text = "Удержание дужки активирует голосовой ассистент"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
        }
        heroCard.addView(statusHintView)
        content.addView(heroCard)

        val pillsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }

        val pillsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun stylePill(tv: TextView) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            tv.typeface = fontMedium
            tv.setPadding(dp(16), dp(11), dp(16), dp(11))
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        }

        pillCarAudioBtn = TextView(activity).apply {
            stylePill(this)
            setOnClickListener {
                isGlassesMediaAudioEnabled = !isGlassesMediaAudioEnabled
                val msg = if (isGlassesMediaAudioEnabled) {
                    "Аудио медиа направлено через очки"
                } else {
                    "Режим Авто: музыка идет на внешнее устройство, ИИ и звонки — в очках"
                }
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        pillVideoRecordBtn = TextView(activity).apply {
            stylePill(this)
            setOnClickListener {
                if (isRecordingVideo) stopVideoRecordingWithTimer() else startVideoRecordingWithTimer()
            }
        }

        pillVideoLimitBtn = TextView(activity).apply {
            stylePill(this)
            setTextColor(Color.parseColor(textPrimary))
            background = roundedBg(bgPill, 20f, strokeSubtle)
            setOnClickListener { showVideoDurationDialog() }
        }

        val pillPhotoBtn = TextView(activity).apply {
            stylePill(this)
            text = "Сделать фото"
            setTextColor(Color.parseColor(textPrimary))
            background = roundedBg(bgPill, 20f, strokeSubtle)
            setOnClickListener {
                releaseStuckCommandSlotIfIdle()
                onPhotoClick()
            }
        }

        val pillSyncBtn = TextView(activity).apply {
            stylePill(this)
            text = "Перенести медиа"
            setTextColor(Color.parseColor(textPrimary))
            background = roundedBg(bgPill, 20f, strokeSubtle)
            setOnClickListener { startSyncAndOpenMediaTab() }
        }

        pillsRow.addView(pillCarAudioBtn)
        pillsRow.addView(pillVideoRecordBtn)
        pillsRow.addView(pillVideoLimitBtn)
        pillsRow.addView(pillPhotoBtn)
        pillsRow.addView(pillSyncBtn)
        pillsScroll.addView(pillsRow)
        content.addView(pillsScroll)

        updateAudioPillUi()
        updateDurationPillUi()
        updateRecordButtonVisual()

        val dotsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(16)
            }
        }
        dotsRow.addView(View(activity).apply {
            background = roundedBg(metaBlue, 4f)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(5)).apply { rightMargin = dp(5) }
        })
        dotsRow.addView(View(activity).apply {
            background = roundedBg(if (dark) "#374151" else "#D1D5DB", 4f)
            layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).apply { rightMargin = dp(5) }
        })
        dotsRow.addView(View(activity).apply {
            background = roundedBg(if (dark) "#374151" else "#D1D5DB", 4f)
            layoutParams = LinearLayout.LayoutParams(dp(5), dp(5))
        })
        content.addView(dotsRow)

        val galleryHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val galleryTitle = TextView(activity).apply {
            text = "Недавние медиа"
            setTextColor(Color.parseColor(textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = fontBold
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val seeAllLink = TextView(activity).apply {
            text = "Открыть все  >"
            setTextColor(Color.parseColor(textSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = fontMedium
            setOnClickListener { switchTab(1) }
        }

        galleryHeader.addView(galleryTitle)
        galleryHeader.addView(seeAllLink)
        content.addView(galleryHeader)

        val recentCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(bgCard, 20f, strokeSubtle)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        homeRecentGridContainer = recentCard
        content.addView(recentCard)

        scroll.addView(content)
        return scroll
    }

    // ================= ВКЛАДКА 2: МЕДИА =================

    private fun buildMediaTab(
        dark: Boolean,
        bgCard: String,
        textPrimary: String,
        textSecondary: String,
        strokeSubtle: String,
        metaBlue: String
    ): ScrollView {
        val scroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(24))
        }

        transferBannerCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(bgCard, 20f, strokeSubtle)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val transferTopRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val transferTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        transferStatusTitle = TextView(activity).apply {
            text = "Перенос медиа с очков"
            setTextColor(Color.parseColor(textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = fontBold
        }

        transferStatusSubtitle = TextView(activity).apply {
            text = "Файлы загружаются сюда для просмотра и выбора обработки перед сохранением в Галерею"
            setTextColor(Color.parseColor(textSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), dp(10), 0)
        }

        transferTextCol.addView(transferStatusTitle)
        transferTextCol.addView(transferStatusSubtitle)

        val startSyncBtn = TextView(activity).apply {
            text = "Перенести"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = fontMedium
            background = roundedBg(metaBlue, 14f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                releaseStuckCommandSlotIfIdle()
                transferProgressBar?.visibility = View.VISIBLE
                transferProgressBar?.isIndeterminate = true
                transferStatusTitle?.text = "Подключение к очкам по Wi-Fi..."
                transferStatusSubtitle?.text = "Ожидание начала передачи файлов"
                onSyncClick()
            }
        }

        transferTopRow.addView(transferTextCol)
        transferTopRow.addView(startSyncBtn)

        transferProgressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply { topMargin = dp(12) }
        }

        transferBannerCard?.addView(transferTopRow)
        transferBannerCard?.addView(transferProgressBar)
        content.addView(transferBannerCard)

        val gridSectionTitle = TextView(activity).apply {
            text = "Перенесенные файлы"
            setTextColor(Color.parseColor(textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = fontBold
            setPadding(0, 0, 0, dp(10))
        }
        content.addView(gridSectionTitle)

        val fullGridCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(bgCard, 20f, strokeSubtle)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        mediaFullGridContainer = fullGridCard
        content.addView(fullGridCard)

        scroll.addView(content)
        return scroll
    }

    private fun startSyncAndOpenMediaTab() {
        switchTab(1)
        releaseStuckCommandSlotIfIdle()
        transferProgressBar?.visibility = View.VISIBLE
        transferProgressBar?.isIndeterminate = true
        transferStatusTitle?.text = "Подключение к очкам по Wi-Fi..."
        transferStatusSubtitle?.text = "Подготовка к переносу медиафайлов"
        onSyncClick()
    }

    fun saveIncomingFileFromGlasses(
        input: InputStream,
        displayName: String,
        takenTimeMs: Long,
        contentLength: Long,
        onBytesCopied: ((Long, Long) -> Unit)? = null
    ): File? {
        return try {
            val ext = if (displayName.endsWith(".mp4", true)) ".mp4" else ".jpg"
            val prefix = if (ext == ".mp4") "VID_" else "IMG_"
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val cleanName = "$prefix${sdf.format(Date(takenTimeMs))}_${System.currentTimeMillis() % 1000}$ext"
            val targetFile = File(stagingDir, cleanName)

            var copiedBytes = 0L
            var lastUiUpdateMs = 0L

            mainHandler.post {
                transferProgressBar?.visibility = View.VISIBLE
                transferProgressBar?.isIndeterminate = contentLength <= 0L
                transferStatusTitle?.text = "Перенос: $cleanName"
            }

            FileOutputStream(targetFile).use { out ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    copiedBytes += read
                    onBytesCopied?.invoke(copiedBytes, contentLength)

                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateMs > 120L) {
                        lastUiUpdateMs = now
                        updateTransferProgressUi(cleanName, copiedBytes, contentLength)
                    }
                }
                out.flush()
            }

            if (copiedBytes <= 0L || (contentLength > 0L && copiedBytes != contentLength)) {
                targetFile.delete()
                mainHandler.post {
                    transferProgressBar?.visibility = View.GONE
                    transferStatusTitle?.text = "Перенос прерван"
                    transferStatusSubtitle?.text = "Файл передан не полностью, повторите попытку"
                }
                return null
            }

            targetFile.setLastModified(takenTimeMs)
            mainHandler.post {
                transferProgressBar?.visibility = View.GONE
                transferStatusTitle?.text = "Перенос завершен"
                val mb = String.format(Locale.US, "%.1f МБ", copiedBytes / (1024f * 1024f))
                transferStatusSubtitle?.text = "Загружен $cleanName ($mb). Нажмите на превью ниже для просмотра и 2K-обработки."
                refreshAllMediaGrids()
            }
            targetFile
        } catch (e: Exception) {
            Log.e("OpenMetaSync", "Failed saving incoming file", e)
            null
        }
    }

    private fun updateTransferProgressUi(fileName: String, copied: Long, total: Long) {
        mainHandler.post {
            transferProgressBar?.visibility = View.VISIBLE
            val copiedMb = String.format(Locale.US, "%.1f", copied / (1024f * 1024f))
            if (total > 0L) {
                val pct = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                val totalMb = String.format(Locale.US, "%.1f", total / (1024f * 1024f))
                transferProgressBar?.isIndeterminate = false
                transferProgressBar?.progress = pct
                transferStatusTitle?.text = "Перенос с очков: $pct%"
                transferStatusSubtitle?.text = "$fileName • $copiedMb из $totalMb МБ"
            } else {
                transferProgressBar?.isIndeterminate = true
                transferStatusTitle?.text = "Перенос с очков..."
                transferStatusSubtitle?.text = "$fileName • $copiedMb МБ"
            }
        }
    }

    // ================= СЕТКА ПРЕВЬЮ И ОКНО ПРОСМОТРА С ВЫБОРОМ ОБРАБОТКИ =================

    private data class StagedMediaFile(
        val file: File,
        val isVideo: Boolean,
        val durationMs: Long
    )

    fun refreshAllMediaGrids() {
        val homeContainer = homeRecentGridContainer
        val mediaContainer = mediaFullGridContainer
        val dark = isDarkMode
        val cellBg = if (dark) "#1F2430" else "#E5E7EB"
        val textSec = if (dark) "#9CA3AF" else "#6B7280"

        scope.launch(Dispatchers.IO) {
            val allFiles = stagingDir.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".mp4", true) || it.name.endsWith(".jpg", true)) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()

            val stagedItems = allFiles.take(40).map { f ->
                val isVid = f.name.endsWith(".mp4", true)
                val dur = if (isVid) extractVideoDurationMs(f) else 0L
                StagedMediaFile(f, isVid, dur)
            }
            val thumbs = stagedItems.map { item -> createLocalThumbnail(item.file, item.isVideo) }

            withContext(Dispatchers.Main) {
                homeContainer?.let { populateGridContainer(it, stagedItems.take(8), thumbs.take(8), cellBg, textSec) }
                mediaContainer?.let { populateGridContainer(it, stagedItems, thumbs, cellBg, textSec) }
            }
        }
    }

    private fun populateGridContainer(
        container: LinearLayout,
        items: List<StagedMediaFile>,
        thumbs: List<Bitmap?>,
        cellBg: String,
        textSec: String
    ) {
        container.removeAllViews()
        if (items.isEmpty()) {
            val emptyLabel = TextView(activity).apply {
                text = "Нет перенесенных файлов.\nНажмите «Перенести медиа», чтобы загрузить фото и видео с очков."
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(textSec))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(20), dp(28), dp(20), dp(28))
            }
            container.addView(emptyLabel)
            return
        }

        val screenWidth = activity.resources.displayMetrics.widthPixels
        val cellSide = ((screenWidth - dp(64)) / 4).coerceAtLeast(dp(70))
        val rowCount = (items.size + 3) / 4

        for (r in 0 until rowCount) {
            val rowLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (r < rowCount - 1) bottomMargin = dp(4)
                }
            }

            for (c in 0..3) {
                val idx = r * 4 + c
                val cellFrame = FrameLayout(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, cellSide, 1f).apply {
                        if (c < 3) rightMargin = dp(4)
                    }
                    background = roundedBg(cellBg, 10f)
                    clipToOutline = true
                }

                if (idx < items.size) {
                    val item = items[idx]
                    val bmp = thumbs[idx]

                    val img = ImageView(activity).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (bmp != null) setImageBitmap(bmp)
                    }
                    cellFrame.addView(img)

                    if (item.isVideo) {
                        val totalSec = (item.durationMs / 1000L).coerceAtLeast(1L)
                        val durBadge = TextView(activity).apply {
                            text = String.format(Locale.US, "%d:%02d", totalSec / 60L, totalSec % 60L)
                            setTextColor(Color.WHITE)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                            typeface = fontMedium
                            setPadding(dp(5), dp(2), dp(5), dp(2))
                            background = roundedBg("#99000000", 5f)
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                Gravity.BOTTOM or Gravity.END
                            ).apply {
                                bottomMargin = dp(4)
                                rightMargin = dp(4)
                            }
                        }
                        cellFrame.addView(durBadge)
                    }

                    cellFrame.setOnClickListener { showMediaPreviewAndProcessDialog(item) }
                }
                rowLayout.addView(cellFrame)
            }
            container.addView(rowLayout)
        }
    }

    private fun extractVideoDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun createLocalThumbnail(file: File, isVideo: Boolean): Bitmap? {
        return try {
            if (isVideo) {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
            } else {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showMediaPreviewAndProcessDialog(item: StagedMediaFile) {
        val dark = isDarkMode
        val bgDialog = if (dark) "#151821" else "#FFFFFF"
        val bgSecondaryBtn = if (dark) "#222734" else "#F3F4F6"
        val textPrimary = if (dark) "#F3F4F6" else "#111827"
        val textSecondary = if (dark) "#9CA3AF" else "#6B7280"
        val strokeSubtle = if (dark) "#282E3D" else "#E5E7EB"
        val metaBlue = "#0064E0"

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(bgDialog, 22f, strokeSubtle)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val titleView = TextView(activity).apply {
            text = item.file.name
            setTextColor(Color.parseColor(textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = fontBold
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(titleView)

        val previewFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(250)
            ).apply { bottomMargin = dp(14) }
            background = roundedBg("#000000", 14f)
            clipToOutline = true
        }

        var videoViewRef: VideoView? = null
        if (item.isVideo) {
            val vv = VideoView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                setVideoPath(item.file.absolutePath)
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    start()
                }
            }
            videoViewRef = vv
            previewFrame.addView(vv)
        } else {
            val iv = ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(BitmapFactory.decodeFile(item.file.absolutePath))
            }
            previewFrame.addView(iv)
        }
        root.addView(previewFrame)

        val renderStatusText = TextView(activity).apply {
            text = if (item.isVideo) "Выберите режим обработки для сохранения в Галерею:" else "Сохранение фото в Галерею:"
            setTextColor(Color.parseColor(textSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(renderStatusText)

        val renderProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply { bottomMargin = dp(12) }
        }
        root.addView(renderProgress)

        fun actionButton(label: String, isPrimary: Boolean, isDanger: Boolean = false, onClick: () -> Unit): TextView {
            val bg = when {
                isPrimary -> metaBlue
                isDanger -> if (dark) "#2D161B" else "#FEE2E2"
                else -> bgSecondaryBtn
            }
            val txtCol = when {
                isPrimary -> "#FFFFFF"
                isDanger -> "#EF4444"
                else -> textPrimary
            }
            return TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(txtCol))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = fontMedium
                background = roundedBg(bg, 14f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener { onClick() }
            }
        }

        if (item.isVideo) {
            root.addView(actionButton("Обработать в Meta 2K (Стабилизация + Резкость)", isPrimary = true) {
                runVideoProcessingAndExport(item.file, OpenMetaRenderMode.META_FAST_2K, renderStatusText, renderProgress, dialog)
            })
            root.addView(actionButton("Горизонт Lock 2K (Удержание горизонта)", isPrimary = false) {
                runVideoProcessingAndExport(item.file, OpenMetaRenderMode.HORIZON_LOCK_2K, renderStatusText, renderProgress, dialog)
            })
            root.addView(actionButton("Сохранить оригинал без обработки", isPrimary = false) {
                runVideoProcessingAndExport(item.file, OpenMetaRenderMode.RAW_ORIGINAL, renderStatusText, renderProgress, dialog)
            })
        } else {
            root.addView(actionButton("Сохранить с улучшением цвета и резкости", isPrimary = true) {
                exportPhotoToGallery(item.file, enhance = true, dialog)
            })
            root.addView(actionButton("Сохранить оригинал без обработки", isPrimary = false) {
                exportPhotoToGallery(item.file, enhance = false, dialog)
            })
        }

        val bottomRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }

        val deleteBtn = actionButton("Удалить", isPrimary = false, isDanger = true) {
            runCatching { videoViewRef?.stopPlayback() }
            item.file.delete()
            dialog.dismiss()
            refreshAllMediaGrids()
            Toast.makeText(activity, "Файл удален", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) }
        }

        val closeBtn = actionButton("Закрыть", isPrimary = false) {
            runCatching { videoViewRef?.stopPlayback() }
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) }
        }

        bottomRow.addView(deleteBtn)
        bottomRow.addView(closeBtn)
        root.addView(bottomRow)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setOnDismissListener { runCatching { videoViewRef?.stopPlayback() } }
        dialog.show()
    }

    private fun runVideoProcessingAndExport(
        sourceFile: File,
        mode: OpenMetaRenderMode,
        statusLabel: TextView,
        progressBar: ProgressBar,
        dialog: Dialog
    ) {
        if (isRenderingVideo) return
        isRenderingVideo = true
        progressBar.visibility = View.VISIBLE
        statusLabel.text = "Выполняется обработка: ${mode.title}..."

        scope.launch(Dispatchers.IO) {
            val processed = if (mode == OpenMetaRenderMode.RAW_ORIGINAL) {
                sourceFile
            } else {
                renderVideoInternal(sourceFile, mode)
            }

            val tag = when (mode) {
                OpenMetaRenderMode.RAW_ORIGINAL -> "Orig"
                OpenMetaRenderMode.META_FAST_2K -> "Meta2K"
                OpenMetaRenderMode.HORIZON_LOCK_2K -> "Horizon2K"
            }
            val sdf = SimpleDateFormat("dd-MM_HH-mm-ss", Locale.US)
            val exportName = "OpenMeta_${tag}_${sdf.format(Date())}.mp4"
            val savedUri = saveFileToSystemGallery(processed, exportName, isVideo = true)

            if (processed != sourceFile) processed.delete()

            withContext(Dispatchers.Main) {
                isRenderingVideo = false
                progressBar.visibility = View.GONE
                if (savedUri != null) {
                    Toast.makeText(activity, "Сохранено в Галерею: $exportName", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } else {
                    statusLabel.text = "Ошибка сохранения файла в Галерею"
                }
            }
        }
    }

    private fun exportPhotoToGallery(sourceFile: File, enhance: Boolean, dialog: Dialog) {
        scope.launch(Dispatchers.IO) {
            val finalFile = if (enhance) {
                sourceFile.inputStream().use { processPhotoStream(it, sourceFile.name) }
            } else {
                sourceFile
            }
            val sdf = SimpleDateFormat("dd-MM_HH-mm-ss", Locale.US)
            val exportName = "OpenMeta_Photo_${sdf.format(Date())}.jpg"
            val uri = saveFileToSystemGallery(finalFile, exportName, isVideo = false)
            if (finalFile != sourceFile) finalFile.delete()

            withContext(Dispatchers.Main) {
                if (uri != null) {
                    Toast.makeText(activity, "Фото сохранено в Галерею", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun saveFileToSystemGallery(file: File, displayName: String, isVideo: Boolean): Uri? {
        return try {
            val nowMs = System.currentTimeMillis()
            val resolver = activity.contentResolver
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                put(MediaStore.MediaColumns.DATE_ADDED, nowMs / 1000L)
                put(MediaStore.MediaColumns.DATE_MODIFIED, nowMs / 1000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/OpenMeta")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri, "w")?.use { out ->
                file.inputStream().use { it.copyTo(out) }
                out.flush()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e("OpenMetaExport", "Gallery save failed", e)
            null
        }
    }

    // ================= ЗАПИСЬ ВИДЕО ДО 12 МИНУТ С ТАЙМЕРОМ И ОСТАНОВКОЙ =================

    private fun updateAudioPillUi() {
        val dark = isDarkMode
        val bgPill = if (dark) "#1C202B" else "#FFFFFF"
        val textPrimary = if (dark) "#F3F4F6" else "#111827"
        val strokeSubtle = if (dark) "#262B38" else "#E5E7EB"

        if (isGlassesMediaAudioEnabled) {
            pillCarAudioBtn?.text = "Аудио: Очки"
            pillCarAudioBtn?.setTextColor(Color.parseColor(textPrimary))
            pillCarAudioBtn?.background = roundedBg(bgPill, 20f, strokeSubtle)
        } else {
            pillCarAudioBtn?.text = "Режим Авто (Без музыки)"
            pillCarAudioBtn?.setTextColor(Color.WHITE)
            pillCarAudioBtn?.background = roundedBg("#0064E0", 20f)
        }
    }

    private fun updateDurationPillUi() {
        val sec = selectedVideoDurationSec
        val label = if (sec < 60) "$sec сек" else "${sec / 60} мин"
        pillVideoLimitBtn?.text = "Длина видео: $label"
    }

    private fun updateRecordButtonVisual() {
        val dark = isDarkMode
        val bgPill = if (dark) "#1C202B" else "#FFFFFF"
        val textPrimary = if (dark) "#F3F4F6" else "#111827"
        val strokeSubtle = if (dark) "#262B38" else "#E5E7EB"

        if (isRecordingVideo) {
            pillVideoRecordBtn?.text = "Остановить запись"
            pillVideoRecordBtn?.setTextColor(Color.WHITE)
            pillVideoRecordBtn?.background = roundedBg("#EF4444", 20f)
            recordingCardLayout?.visibility = View.VISIBLE
        } else {
            pillVideoRecordBtn?.text = "Запись видео"
            pillVideoRecordBtn?.setTextColor(Color.parseColor(textPrimary))
            pillVideoRecordBtn?.background = roundedBg(bgPill, 20f, strokeSubtle)
            recordingCardLayout?.visibility = View.GONE
        }
    }

    private fun showVideoDurationDialog() {
        val titles = arrayOf(
            "15 секунд",
            "30 секунд",
            "1 минута",
            "3 минуты",
            "6 минут",
            "9 минут",
            "12 минут"
        )
        val seconds = intArrayOf(15, 30, 60, 180, 360, 540, 720)
        val checked = seconds.indexOf(selectedVideoDurationSec).coerceAtLeast(2)

        AlertDialog.Builder(activity)
            .setTitle("Длительность записи видео")
            .setSingleChoiceItems(titles, checked) { d, which ->
                selectedVideoDurationSec = seconds[which]
                d.dismiss()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun startVideoRecordingWithTimer() {
        releaseStuckCommandSlotIfIdle()
        onVideoClick()
        isRecordingVideo = true
        updateRecordButtonVisual()

        val totalMs = selectedVideoDurationSec * 1000L
        activeVideoTimer?.cancel()
        activeVideoTimer = object : CountDownTimer(totalMs, 500L) {
            override fun onTick(millisUntilFinished: Long) {
                val secLeft = (millisUntilFinished / 1000L).coerceAtLeast(0L)
                val minPart = secLeft / 60L
                val secPart = secLeft % 60L
                recordingTimerLabel?.text = String.format(
                    Locale.US,
                    "Запись видео • Осталось %02d:%02d",
                    minPart,
                    secPart
                )
                val progress = ((millisUntilFinished * 1000L) / totalMs).toInt().coerceIn(0, 1000)
                recordingProgressBar?.progress = progress
            }

            override fun onFinish() {
                stopVideoRecordingWithTimer()
            }
        }.start()
    }

    private fun stopVideoRecordingWithTimer() {
        activeVideoTimer?.cancel()
        activeVideoTimer = null
        if (isRecordingVideo) {
            releaseStuckCommandSlotIfIdle()
            onVideoClick()
        }
        isRecordingVideo = false
        updateRecordButtonVisual()
        statusHintView?.text = "Запись завершена. Нажмите «Перенести медиа», чтобы загрузить видео."
    }

    fun updateStatusUi(connected: Boolean, battery: Int?) {
        lastKnownConnected = connected
        if (battery != null) lastKnownBattery = battery

        mainHandler.post {
            val tuneBudsConnected = runCatching { TuneBudsManager.getInstance(activity).isConnected() }.getOrDefault(false)
            val isOnline = connected || tuneBudsConnected || (lastKnownBattery != null && lastKnownBattery!! > 0)
            val pct = lastKnownBattery ?: if (isOnline) 100 else 0

            batteryIconView?.updateLevel(pct, isOnline)
            batteryTextView?.text = if (isOnline) {
                "${lastKnownBattery?.let { "$it%" } ?: "Подключено"}"
            } else {
                "Не подключено"
            }

            if (isOnline && !isGlassesMediaAudioEnabled) {
                applyGlassesA2dpRoute(false)
            }
        }
    }

    // ================= ЕДИНОЕ ОКНО НАСТРОЕК (АССИСТЕНТ + ТЕСТЫ + ПЕРЕХОД В СТОК) =================

    private fun showUnifiedSettingsDialog() {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

        val keyLabel = TextView(activity).apply {
            text = "API-ключ Google Gemini"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = fontMedium
        }
        val keyInput = EditText(activity).apply {
            hint = "AIzaSy..."
            setText(geminiApiKey)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }

        val proxyLabel = TextView(activity).apply {
            text = "Адрес сервера API (или прокси для работы без VPN)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = fontMedium
            setPadding(0, dp(10), 0, 0)
        }
        val proxyInput = EditText(activity).apply {
            hint = "https://generativelanguage.googleapis.com"
            setText(geminiBaseUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }

        fun dialogActionBtn(title: String, onClick: () -> Unit): Button {
            return Button(activity).apply {
                text = title
                isAllCaps = false
                typeface = fontMedium
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setOnClickListener { onClick() }
            }
        }

        container.addView(keyLabel)
        container.addView(keyInput)
        container.addView(proxyLabel)
        container.addView(proxyInput)

        lateinit var dialog: AlertDialog

        container.addView(dialogActionBtn("Проверить голосовой вызов ИИ") {
            geminiApiKey = keyInput.text.toString()
            geminiBaseUrl = proxyInput.text.toString()
            dialog.dismiss()
            onTouchpadLongHold()
        })

        container.addView(dialogActionBtn("Тест камеры ИИ («Посмотри, что это»)") {
            geminiApiKey = keyInput.text.toString()
            geminiBaseUrl = proxyInput.text.toString()
            dialog.dismiss()
            handleRussianVoiceCommand("Посмотри и коротко скажи, что передо мной")
        })

        container.addView(dialogActionBtn("Назначить ассистента на дужку по умолчанию") {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }.onFailure {
                runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            }
        })

        container.addView(dialogActionBtn("Мастер первичной настройки и разрешений") {
            dialog.dismiss()
            showFirstLaunchOnboardingDialog()
        })

        container.addView(dialogActionBtn("Открыть стоковый интерфейс CyanBridge") {
            dialog.dismiss()
            mainWrapperLayout?.visibility = View.GONE
            floatingBackToMetaBtn?.visibility = View.VISIBLE
        })

        dialog = AlertDialog.Builder(activity)
            .setTitle("Настройки OpenMeta")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                geminiApiKey = keyInput.text.toString()
                geminiBaseUrl = proxyInput.text.toString()
                Toast.makeText(activity, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .create()

        dialog.show()
    }

    // ================= МАСТЕР ПЕРВОГО ЗАПУСКА =================

    private fun showFirstLaunchOnboardingDialog() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(8))
        }

        val desc = TextView(activity).apply {
            text = "1. Выберите профиль ваших очков.\n2. Выдайте разрешения для Bluetooth, микрофона и переноса медиа.\n3. Подключите очки."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(12))
        }
        box.addView(desc)

        val profileNames = arrayOf("TuneBuds / AI Glasses", "HeyCyan Glasses")
        val savedProfileName = prefs.getString("selected_hw_profile_label", profileNames[0]) ?: profileNames[0]

        val profileBtn = Button(activity).apply {
            isAllCaps = false
            text = "Профиль очков: $savedProfileName"
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("Выберите профиль очков")
                    .setItems(profileNames) { _, which ->
                        val chosen = profileNames[which]
                        prefs.edit().putString("selected_hw_profile_label", chosen).apply()
                        text = "Профиль очков: $chosen"
                    }
                    .show()
            }
        }
        box.addView(profileBtn)

        val permBtn = Button(activity).apply {
            isAllCaps = false
            text = "Выдать все разрешения"
            setOnClickListener { requestAllNecessaryPermissions() }
        }
        box.addView(permBtn)

        val pairBtn = Button(activity).apply {
            isAllCaps = false
            text = "Найти и подключить очки"
            setOnClickListener {
                prefs.edit().putBoolean("onboarding_completed", true).apply()
                releaseStuckCommandSlotIfIdle()
                onScanClick()
            }
        }
        box.addView(pairBtn)

        AlertDialog.Builder(activity)
            .setTitle("Добро пожаловать в OpenMeta")
            .setView(box)
            .setPositiveButton("Готово") { _, _ ->
                prefs.edit().putBoolean("onboarding_completed", true).apply()
            }
            .show()
    }

    private fun requestAllNecessaryPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        activity.requestPermissions(perms.toTypedArray(), 777)
    }

    // ================= ИСПРАВЛЕННЫЙ ВЫЗОВ ИИ С ДУЖКИ =================

    fun onTouchpadLongHold() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < 1000L) return
        lastTriggerTimeMs = now

        mainHandler.post {
            releaseStuckCommandSlotIfIdle()
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestAllNecessaryPermissions()
                speakRussian("Разрешите доступ к микрофону на экране телефона")
                return@post
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
            }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            tts?.stop()
            audioManager.mode = AudioManager.MODE_NORMAL

            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                mainHandler.postDelayed({ tone.release() }, 160)
            }

            statusHintView?.text = "Слушаю... Произнесите вопрос или «Посмотри, что это»"
            mainHandler.postDelayed({ startHandsFreeListening() }, 350)
        }
    }

    private fun startHandsFreeListening() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity.applicationContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        statusHintView?.text = "Микрофон активен — говорите..."
                    }

                    override fun onBeginningOfSpeech() {
                        statusHintView?.text = "Распознавание речи..."
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val query = matches?.firstOrNull()?.trim().orEmpty()
                        if (query.isNotEmpty()) {
                            statusHintView?.text = "Запрос: «$query»"
                            handleRussianVoiceCommand(query)
                        } else {
                            statusHintView?.text = "Речь не распознана"
                            speakRussian("Не расслышал, повторите")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (partial.isNotEmpty()) {
                            statusHintView?.text = "Слышу: «$partial»..."
                        }
                    }

                    override fun onError(error: Int) {
                        Log.w("OpenMetaAI", "SpeechRecognizer error=$error")
                        statusHintView?.text = "Удержание дужки активирует голосовой ассистент"
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            speakRussian("Не расслышал")
                        }
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("OpenMetaAI", "SpeechRecognizer failed", e)
            speakRussian("Сервис распознавания речи недоступен")
        }
    }

    fun handleRussianVoiceCommand(query: String) {
        val lower = query.lowercase(Locale("ru", "RU"))
        val needsCamera = visionTriggers.any { lower.contains(it) }

        if (geminiApiKey.isBlank()) {
            speakRussian("Сначала укажите ключ нейросети в меню настроек")
            showUnifiedSettingsDialog()
            return
        }

        if (needsCamera) {
            lastUserVisionQuery = query
            speakRussian("Секунду, смотрю")
            statusHintView?.text = "Получение кадра с очков..."

            scope.launch(Dispatchers.IO) {
                val tuneBuds = TuneBudsManager.getInstance(activity)
                if (tuneBuds.isConnected()) {
                    val photoBytes = runCatching { tuneBuds.capturePhotoForAi() }.getOrNull()
                    if (photoBytes != null && photoBytes.isNotEmpty()) {
                        val answer = askGeminiDirect(query, photoBytes)
                        withContext(Dispatchers.Main) {
                            statusHintView?.text = answer
                            speakRussian(answer)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            statusHintView?.text = "Не удалось получить снимок с очков"
                            speakRussian("Не удалось получить кадр с очков, попробуйте снова")
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

        statusHintView?.text = "Обработка запроса в Gemini..."
        scope.launch(Dispatchers.IO) {
            val reply = askGeminiDirect(query, null)
            withContext(Dispatchers.Main) {
                statusHintView?.text = reply
                speakRussian(reply)
            }
        }
    }

    fun askGeminiDirect(prompt: String, imageBytes: ByteArray?): String {
        val models = listOf("gemini-2.5-flash", "gemini-2.0-flash")
        var lastError = "ошибка сети"

        for (modelName in models) {
            try {
                val sysPrompt = "Ты голосовой ассистент в умных очках пользователя. " +
                    "Отвечай на русском языке, коротко и по делу (1-3 предложения), " +
                    "без списков и спецсимволов, так как ответ сразу озвучивается голосом."

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
                    if (errBody.contains("User location is not supported", ignoreCase = true) ||
                        errBody.contains("FAILED_PRECONDITION", ignoreCase = true)
                    ) {
                        return "Сервер Гугл отклонил прямой запрос без ВПН или прокси."
                    }
                    if (code == 400 && errBody.contains("API_KEY_INVALID", ignoreCase = true)) {
                        return "Указан неверный ключ нейросети в настройках."
                    }
                    lastError = "код $code"
                }
            } catch (_: Exception) {
                lastError = "нет связи с сервером"
            }
        }
        return "Не удалось связаться с нейросетью, $lastError."
    }

    fun speakRussian(text: String) {
        val useVoiceCallChannel = !isGlassesMediaAudioEnabled
        if (useVoiceCallChannel) {
            startGlassesVoiceCallChannel()
        }
        val params = Bundle().apply {
            putInt(
                TextToSpeech.Engine.KEY_PARAM_STREAM,
                if (useVoiceCallChannel) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            )
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "OPENMETA_RU_${System.currentTimeMillis()}")
    }

    // ================= ПАЙПЛАЙН ОБРАБОТКИ ФОТО И ВИДЕО 2K =================

    fun processPhotoStream(rawInput: InputStream, fileName: String): File {
        val tempRaw = File(activity.cacheDir, "raw_$fileName")
        val tempOut = File(activity.cacheDir, "out_$fileName")
        FileOutputStream(tempRaw).use { rawInput.copyTo(it) }

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
        } catch (_: Exception) {
            tempRaw
        }
    }

    suspend fun processVideoFile(rawVideoFile: File): File = rawVideoFile

    private suspend fun renderVideoInternal(rawVideoFile: File, mode: OpenMetaRenderMode): File {
        if (mode == OpenMetaRenderMode.RAW_ORIGINAL) return rawVideoFile

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
                            outFile.delete()
                            deferred.complete(rawVideoFile)
                        }
                    })
                    .build()

                transformer.start(editedItem, outFile.absolutePath)
            }
            deferred.await()
        } catch (_: Exception) {
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

    private fun circleBg(fillHex: String, strokeColor: String? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(fillHex))
            if (strokeColor != null) {
                setStroke(dp(1), Color.parseColor(strokeColor))
            }
        }
}

class BatteryVectorView(context: Context, private var tintColor: Int) : View(context) {
    private var level: Int = 100
    private var online: Boolean = false
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun updateLevel(pct: Int, isConnected: Boolean) {
        level = pct.coerceIn(0, 100)
        online = isConnected
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        strokePaint.color = tintColor
        fillPaint.color = if (!online) Color.GRAY else if (level <= 15) Color.parseColor("#EF4444") else tintColor

        val bodyRect = RectF(2f, 2f, w - 5f, h - 2f)
        canvas.drawRoundRect(bodyRect, 4f, 4f, strokePaint)
        canvas.drawRoundRect(RectF(w - 4f, h * 0.3f, w - 1f, h * 0.7f), 2f, 2f, fillPaint)

        if (online) {
            val fillWidth = (bodyRect.width() - 6f) * (level / 100f)
            val innerRect = RectF(bodyRect.left + 3f, bodyRect.top + 3f, bodyRect.left + 3f + fillWidth, bodyRect.bottom - 3f)
            canvas.drawRoundRect(innerRect, 2f, 2f, fillPaint)
        }
    }
}

class VectorIconView(context: Context, private val type: Int, private var color: Int) : View(context) {
    companion object {
        const val TYPE_PLUS = 1
        const val TYPE_GEAR = 2
        const val TYPE_MOON = 3
        const val TYPE_SUN = 4
        const val TYPE_GLASSES = 5
        const val TYPE_GRID = 6
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setTint(newColor: Int) {
        color = newColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = color
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f

        when (type) {
            TYPE_PLUS -> {
                canvas.drawLine(cx, h * 0.2f, cx, h * 0.8f, paint)
                canvas.drawLine(w * 0.2f, cy, w * 0.8f, cy, paint)
            }
            TYPE_GEAR -> {
                val rOuter = min(w, h) * 0.38f
                val rInner = min(w, h) * 0.18f
                canvas.drawCircle(cx, cy, rInner, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val x1 = cx + (rInner * 1.35f) * cos(a).toFloat()
                    val y1 = cy + (rInner * 1.35f) * sin(a).toFloat()
                    val x2 = cx + rOuter * cos(a).toFloat()
                    val y2 = cy + rOuter * sin(a).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
            }
            TYPE_MOON -> {
                val r = min(w, h) * 0.34f
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 40f, 260f, false, paint)
            }
            TYPE_SUN -> {
                val r = min(w, h) * 0.22f
                canvas.drawCircle(cx, cy, r, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    canvas.drawLine(
                        cx + r * 1.4f * cos(a).toFloat(),
                        cy + r * 1.4f * sin(a).toFloat(),
                        cx + r * 1.9f * cos(a).toFloat(),
                        cy + r * 1.9f * sin(a).toFloat(),
                        paint
                    )
                }
            }
            TYPE_GLASSES -> {
                val leftRect = RectF(w * 0.08f, h * 0.28f, w * 0.44f, h * 0.76f)
                val rightRect = RectF(w * 0.56f, h * 0.28f, w * 0.92f, h * 0.76f)
                canvas.drawRoundRect(leftRect, 6f, 6f, paint)
                canvas.drawRoundRect(rightRect, 6f, 6f, paint)
                canvas.drawLine(w * 0.44f, h * 0.42f, w * 0.56f, h * 0.42f, paint)
            }
            TYPE_GRID -> {
                val pad = w * 0.14f
                val gap = w * 0.08f
                val s = (w - pad * 2 - gap) / 2f
                canvas.drawRoundRect(RectF(pad, pad, pad + s, pad + s), 4f, 4f, paint)
                canvas.drawRoundRect(RectF(pad + s + gap, pad, w - pad, pad + s), 4f, 4f, paint)
                canvas.drawRoundRect(RectF(pad, pad + s + gap, pad + s, h - pad), 4f, 4f, paint)
                canvas.drawRoundRect(RectF(pad + s + gap, pad + s + gap, w - pad, h - pad), 4f, 4f, paint)
            }
        }
    }
}

class WayfarerGlassesView(context: Context, private val isDark: Boolean) : View(context) {
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (isDark) "#2A2E39" else "#1D1F23")
        style = Paint.Style.FILL
    }
    private val frameOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (isDark) "#4B5563" else "#111315")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cameraRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9CA3AF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val cameraCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#090A0C")
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(if (isDark) 60 else 28, 0, 0, 0)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.48f
        val scale = min(w / 340f, h / 120f)

        canvas.drawOval(
            RectF(cx - 115f * scale, cy + 44f * scale, cx + 115f * scale, cy + 54f * scale),
            shadowPaint
        )

        lensPaint.shader = LinearGradient(
            cx, cy - 35f * scale, cx, cy + 35f * scale,
            Color.parseColor("#2B3330"),
            Color.parseColor("#141817"),
            Shader.TileMode.CLAMP
        )

        val framePath = Path().apply {
            moveTo(cx - 135f * scale, cy - 26f * scale)
            quadTo(cx - 72f * scale, cy - 34f * scale, cx - 16f * scale, cy - 22f * scale)
            quadTo(cx, cy - 25f * scale, cx + 16f * scale, cy - 22f * scale)
            quadTo(cx + 72f * scale, cy - 34f * scale, cx + 135f * scale, cy - 26f * scale)
            lineTo(cx + 130f * scale, cy - 8f * scale)
            quadTo(cx + 122f * scale, cy + 36f * scale, cx + 72f * scale, cy + 38f * scale)
            quadTo(cx + 26f * scale, cy + 36f * scale, cx + 15f * scale, cy - 6f * scale)
            quadTo(cx, cy - 12f * scale, cx - 15f * scale, cy - 6f * scale)
            quadTo(cx - 26f * scale, cy + 36f * scale, cx - 72f * scale, cy + 38f * scale)
            quadTo(cx - 122f * scale, cy + 36f * scale, cx - 130f * scale, cy - 8f * scale)
            close()
        }
        canvas.drawPath(framePath, framePaint)
        canvas.drawPath(framePath, frameOutlinePaint)

        val leftLens = RectF(cx - 114f * scale, cy - 21f * scale, cx - 24f * scale, cy + 29f * scale)
        val rightLens = RectF(cx + 24f * scale, cy - 21f * scale, cx + 114f * scale, cy + 29f * scale)
        canvas.drawRoundRect(leftLens, 18f * scale, 22f * scale, lensPaint)
        canvas.drawRoundRect(rightLens, 18f * scale, 22f * scale, lensPaint)

        val camY = cy - 16f * scale
        val leftCamX = cx - 122f * scale
        val rightCamX = cx + 122f * scale
        canvas.drawCircle(leftCamX, camY, 5.2f * scale, cameraCorePaint)
        canvas.drawCircle(leftCamX, camY, 5.2f * scale, cameraRingPaint)
        canvas.drawCircle(rightCamX, camY, 5.2f * scale, cameraCorePaint)
        canvas.drawCircle(rightCamX, camY, 5.2f * scale, cameraRingPaint)
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
