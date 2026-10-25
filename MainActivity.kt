package com.jarvis.tv

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.*
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    data class InstalledApp(
        val name: String,
        val normalizedName: String,
        val packageName: String,
        val isTvApp: Boolean
    )

    private lateinit var hudView: JarvisHudView
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var responseText: TextView
    private lateinit var clockText: TextView
    private lateinit var commandInput: EditText
    private lateinit var micButton: Button
    private lateinit var appsContainer: LinearLayout

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val installedApps = mutableListOf<InstalledApp>()

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
        private const val REQ_VOICE_FALLBACK = 1002
    }

    private val clockTicker = object : Runnable {
        override fun run() {
            val sdf = SimpleDateFormat("EEE, MMM d  •  h:mm:ss a", Locale.getDefault())
            clockText.text = "SYS.ONLINE  |  ${sdf.format(Date())}"
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildSleekTvUi())

        tts = TextToSpeech(applicationContext, this)
        loadInstalledApps()
        ensureMicPermission()
        initSpeechRecognizer()
    }

    private fun buildSleekTvUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050B14"))
            setPadding(dp(32), dp(24), dp(32), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleText = TextView(this).apply {
            text = "J.A.R.V.I.S. // MARK XIV OFFLINE AI"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        clockText = TextView(this).apply {
            text = "SYS.ONLINE"
            setTextColor(Color.parseColor("#80D8FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
        }

        header.addView(titleText)
        header.addView(clockText)
        root.addView(header)

        val centerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        }

        hudView = JarvisHudView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(250)).apply {
                rightMargin = dp(28)
            }
        }
        centerRow.addView(hudView)

        val consolePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createPanelDrawable("#0A1626", "#3300E5FF")
            setPadding(dp(24), dp(20), dp(24), dp(20))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        statusText = TextView(this).apply {
            text = "STANDBY — PRESS OK ON MIC OR SPEAK COMMAND"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        transcriptText = TextView(this).apply {
            text = "Try: \"Open YouTube\"  •  \"System Status\"  •  \"What time is it?\""
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, dp(8), 0, dp(8))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        responseText = TextView(this).apply {
            text = "All offline subsystems are nominal, sir. Ready for your command."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        micButton = createTvButton("SPEAK COMMAND", isPrimary = true).apply {
            setOnClickListener { toggleListening() }
        }

        commandInput = EditText(this).apply {
            hint = "Or type command (e.g. open netflix)..."
            setHintTextColor(Color.parseColor("#546E7A"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = createPanelDrawable("#102238", "#4400E5FF")
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
            setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    val cmd = v.text.toString().trim()
                    if (cmd.isNotEmpty()) {
                        v.text = ""
                        processCommand(cmd)
                    }
                    true
                } else false
            }
        }

        val statusBtn = createTvButton("DIAGNOSTICS").apply {
            setOnClickListener { processCommand("system status") }
        }

        controlsRow.addView(micButton)
        controlsRow.addView(commandInput)
        controlsRow.addView(statusBtn)

        consolePanel.addView(statusText)
        consolePanel.addView(transcriptText)
        consolePanel.addView(responseText)
        consolePanel.addView(controlsRow)

        centerRow.addView(consolePanel)
        root.addView(centerRow)

        val dockLabel = TextView(this).apply {
            text = "INSTALLED APPS  (SAY \"OPEN [APP NAME]\" OR SELECT WITH D-PAD)"
            setTextColor(Color.parseColor("#64B5F6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(dockLabel)

        val scrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        appsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        scrollView.addView(appsContainer)
        root.addView(scrollView)

        micButton.requestFocus()
        return root
    }

    private fun createTvButton(label: String, isPrimary: Boolean = false): Button {
        val baseBg = if (isPrimary) "#004D66" else "#102238"
        val baseStroke = if (isPrimary) "#00E5FF" else "#3380D8FF"
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = false
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = createPanelDrawable(baseBg, baseStroke)
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.background = createPanelDrawable("#00838F", "#FFFFFF", strokeWidthDp = 3)
                    v.scaleX = 1.06f
                    v.scaleY = 1.06f
                } else {
                    v.background = createPanelDrawable(baseBg, baseStroke, strokeWidthDp = 1)
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f
                }
            }
        }
    }

    private fun createPanelDrawable(
        fillHex: String,
        strokeHex: String,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(strokeWidthDp), Color.parseColor(strokeHex))
        }
    }

    private fun loadInstalledApps() {
        installedApps.clear()
        appsContainer.removeAllViews()

        val pm = packageManager
        val seenPackages = HashSet<String>()

        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val standardIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val tvResolveInfos = queryActivitiesCompat(pm, leanbackIntent)
        val stdResolveInfos = queryActivitiesCompat(pm, standardIntent)

        for (info in tvResolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg != packageName && seenPackages.add(pkg)) {
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                installedApps.add(InstalledApp(label, normalizeText(label), pkg, isTvApp = true))
            }
        }

        for (info in stdResolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg != packageName && seenPackages.add(pkg)) {
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                installedApps.add(InstalledApp(label, normalizeText(label), pkg, isTvApp = false))
            }
        }

        installedApps.sortWith(compareByDescending<InstalledApp> { it.isTvApp }.thenBy { it.name.lowercase(Locale.ROOT) })

        for (app in installedApps) {
            val chip = createTvButton(app.name).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(10) }
                layoutParams = params
                setOnClickListener { launchApp(app) }
            }
            appsContainer.addView(chip)
        }
    }

    @Suppress("DEPRECATION")
    private fun queryActivitiesCompat(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }

    private fun launchApp(app: InstalledApp) {
        try {
            val pm = packageManager
            val launchIntent = pm.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: pm.getLaunchIntentForPackage(app.packageName)

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                respond("Launching ${app.name}, sir.")
                startActivity(launchIntent)
            } else {
                respond("I located ${app.name}, but it does not expose a launchable screen.")
            }
        } catch (e: Exception) {
            respond("Unable to open ${app.name}: ${e.localizedMessage ?: "Launch error"}")
        }
    }

    private fun ensureMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        }
    }

    private fun initSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
                SpeechRecognizer.createSpeechRecognizer(this)
            } else {
                null
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    hudView.setMode(JarvisHudView.Mode.LISTENING)
                    statusText.text = "LISTENING (OFFLINE ENGINE ACTIVE)..."
                    micButton.text = "STOP LISTENING"
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    hudView.setAudioLevel(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    hudView.setMode(JarvisHudView.Mode.IDLE)
                    statusText.text = "PROCESSING COMMAND..."
                    micButton.text = "SPEAK COMMAND"
                }

                override fun onError(error: Int) {
                    isListening = false
                    hudView.setMode(JarvisHudView.Mode.IDLE)
                    micButton.text = "SPEAK COMMAND"
                    statusText.text = "READY — PRESS OK TO SPEAK OR TYPE BELOW"
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    hudView.setMode(JarvisHudView.Mode.IDLE)
                    micButton.text = "SPEAK COMMAND"
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull()?.trim().orEmpty()
                    if (best.isNotEmpty()) {
                        processCommand(best)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        transcriptText.text = "Heard: \"$partial\""
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } catch (_: Exception) {
            speechRecognizer = null
        }
    }

    private fun toggleListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ensureMicPermission()
            return
        }

        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            isListening = false
            hudView.setMode(JarvisHudView.Mode.IDLE)
            micButton.text = "SPEAK COMMAND"
            statusText.text = "STANDBY"
            return
        }

        tts?.stop()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        if (speechRecognizer != null) {
            try {
                speechRecognizer?.startListening(intent)
                return
            } catch (_: Exception) {
                initSpeechRecognizer()
            }
        }

        try {
            startActivityForResult(intent, REQ_VOICE_FALLBACK)
        } catch (_: ActivityNotFoundException) {
            respond("No microphone service found on this TV. Please type your command or select an app below.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE_FALLBACK && resultCode == RESULT_OK && data != null) {
            val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val best = matches?.firstOrNull()?.trim().orEmpty()
            if (best.isNotEmpty()) {
                processCommand(best)
            }
        }
    }

    private fun processCommand(rawCommand: String) {
        transcriptText.text = "Command: \"$rawCommand\""
        val cmd = normalizeText(rawCommand)

        if (cmd.isEmpty()) return

        if (cmd.contains("wifi") || cmd.contains("network") || cmd.contains("internet")) {
            respond("Opening network configuration, sir.")
            startActivitySafely(Intent(Settings.ACTION_WIFI_SETTINGS))
            return
        }
        if (cmd.contains("bluetooth") || cmd.contains("remote")) {
            respond("Opening Bluetooth and remote settings.")
            startActivitySafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        if (cmd == "settings" || cmd == "open settings" || cmd == "system settings") {
            respond("Opening Android TV system settings.")
            startActivitySafely(Intent(Settings.ACTION_SETTINGS))
            return
        }

        val launchPrefixes = listOf("open ", "launch ", "start ", "run ", "go to ", "play ")
        for (prefix in launchPrefixes) {
            if (cmd.startsWith(prefix)) {
                val targetName = cmd.removePrefix(prefix).trim()
                if (targetName.isNotEmpty()) {
                    val matchedApp = findBestAppMatch(targetName)
                    if (matchedApp != null) {
                        launchApp(matchedApp)
                    } else {
                        respond("I could not find an installed application matching '$targetName'.")
                    }
                    return
                }
            }
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (cmd.contains("volume up") || cmd.contains("louder") || cmd.contains("turn it up")) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
            respond("Increasing audio output.")
            return
        }
        if (cmd.contains("volume down") || cmd.contains("quieter") || cmd.contains("softer")) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            respond("Decreasing audio output.")
            return
        }
        if (cmd.contains("mute") || cmd.contains("silence")) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                AudioManager.FLAG_SHOW_UI
            )
            respond("Audio muted, sir.")
            return
        }

        when {
            cmd.contains("time") -> {
                val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                respond("The current time is $timeStr, sir.")
            }
            cmd.contains("date") || cmd.contains("day is it") -> {
                val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                respond("Today is $dateStr.")
            }
            cmd.contains("status") || cmd.contains("diagnostic") || cmd.contains("memory") || cmd.contains("health") -> {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val availMb = memInfo.availMem / (1024 * 1024)
                val totalMb = memInfo.totalMem / (1024 * 1024)
                val uptimeHours = SystemClock.elapsedRealtime() / (1000 * 60 * 60)
                val uptimeMins = (SystemClock.elapsedRealtime() / (1000 * 60)) % 60
                respond(
                    "All systems functioning within normal parameters. " +
                        "Available memory: $availMb MB of $totalMb MB. " +
                        "System uptime: $uptimeHours hours, $uptimeMins minutes. " +
                        "${installedApps.size} applications indexed."
                )
            }
            cmd.contains("refresh") || cmd.contains("rescan") || cmd.contains("reload") -> {
                loadInstalledApps()
                respond("Application index refreshed. ${installedApps.size} apps ready.")
            }
            cmd.contains("who are you") || cmd.contains("your name") || cmd.contains("jarvis") -> {
                respond("I am J.A.R.V.I.S., your offline Android 14 Television system intelligence.")
            }
            cmd.contains("hello") || cmd.contains("good morning") || cmd.contains("good evening") || cmd == "hi" -> {
                respond("Greetings, sir. All systems are online and awaiting your instructions.")
            }
            else -> {
                val directApp = findBestAppMatch(cmd)
                if (directApp != null) {
                    launchApp(directApp)
                } else {
                    respond("Command '$rawCommand' not recognized. Say 'Open' followed by an app name, or ask for 'System Status'.")
                }
            }
        }
    }

    private fun findBestAppMatch(query: String): InstalledApp? {
        val q = normalizeText(query)
        if (q.isEmpty()) return null

        installedApps.firstOrNull { it.normalizedName == q }?.let { return it }
        installedApps.firstOrNull { it.normalizedName.startsWith(q) }?.let { return it }
        installedApps.firstOrNull { it.normalizedName.contains(q) || q.contains(it.normalizedName) }?.let { return it }

        val queryWords = q.split(" ").filter { it.length > 1 }
        if (queryWords.isNotEmpty()) {
            return installedApps.firstOrNull { app ->
                queryWords.all { word -> app.normalizedName.contains(word) }
            }
        }
        return null
    }

    private fun normalizeText(input: String): String {
        return input.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            respond("That system screen is unavailable on this TV model.")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.UK)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
            tts?.setPitch(0.88f)
            tts?.setSpeechRate(1.02f)
            isTtsReady = true
            speak("Jarvis offline systems online.")
        }
    }

    private fun respond(message: String) {
        statusText.text = "J.A.R.V.I.S. RESPONSE"
        responseText.text = message
        speak(message)
    }

    private fun speak(text: String) {
        if (!isTtsReady) return
        hudView.setMode(JarvisHudView.Mode.SPEAKING)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_UTTERANCE")
        mainHandler.postDelayed({
            if (!isListening) hudView.setMode(JarvisHudView.Mode.IDLE)
        }, 2600L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
            keyCode == KeyEvent.KEYCODE_SEARCH ||
            keyCode == KeyEvent.KEYCODE_MEDIA_RECORD
        ) {
            toggleListening()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        hudView.setRunning(true)
        mainHandler.removeCallbacks(clockTicker)
        mainHandler.post(clockTicker)
    }

    override fun onPause() {
        super.onPause()
        hudView.setRunning(false)
        mainHandler.removeCallbacks(clockTicker)
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            isListening = false
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    class JarvisHudView(context: Context) : View(context) {

        enum class Mode { IDLE, LISTENING, SPEAKING }

        private var mode: Mode = Mode.IDLE
        private var isRunning = true
        private var angleOuter = 0f
        private var angleInner = 0f
        private var pulsePhase = 0f
        private var audioBoost = 0f

        private val paintOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.parseColor("#00E5FF")
        }

        private val paintInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            color = Color.parseColor("#29B6F6")
            strokeCap = Paint.Cap.ROUND
        }

        private val paintCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#3300E5FF")
        }

        private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0F7FA")
            textSize = 26f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        private val arcRect = RectF()

        fun setMode(newMode: Mode) {
            mode = newMode
            invalidate()
        }

        fun setAudioLevel(rmsdB: Float) {
            audioBoost = rmsdB.coerceIn(0f, 12f) * 1.8f
        }

        fun setRunning(running: Boolean) {
            isRunning = running
            if (running) invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val maxR = min(cx, cy) * 0.88f

            val speedMultiplier = when (mode) {
                Mode.IDLE -> 1.0f
                Mode.LISTENING -> 2.8f
                Mode.SPEAKING -> 2.0f
            }

            angleOuter = (angleOuter + 0.8f * speedMultiplier) % 360f
            angleInner = (angleInner - 1.4f * speedMultiplier) % 360f
            pulsePhase += 0.07f * speedMultiplier

            val corePulse = (sin(pulsePhase.toDouble()).toFloat() * 8f) + audioBoost
            audioBoost *= 0.85f

            val activeColor = when (mode) {
                Mode.IDLE -> Color.parseColor("#00E5FF")
                Mode.LISTENING -> Color.parseColor("#00E676")
                Mode.SPEAKING -> Color.parseColor("#40C4FF")
            }
            paintOuter.color = activeColor
            paintInner.color = activeColor

            paintCore.color = activeColor
            paintCore.alpha = 45
            canvas.drawCircle(cx, cy, maxR * 0.42f + corePulse, paintCore)
            paintCore.alpha = 95
            canvas.drawCircle(cx, cy, maxR * 0.25f + (corePulse * 0.5f), paintCore)

            arcRect.set(cx - maxR, cy - maxR, cx + maxR, cy + maxR)
            for (i in 0 until 4) {
                canvas.drawArc(arcRect, angleOuter + (i * 90f), 58f, false, paintOuter)
            }

            val midR = maxR * 0.72f
            arcRect.set(cx - midR, cy - midR, cx + midR, cy + midR)
            for (i in 0 until 3) {
                canvas.drawArc(arcRect, angleInner + (i * 120f), 75f, false, paintInner)
            }

            val tickOuter = maxR * 0.86f
            val tickInner = maxR * 0.80f
            for (deg in 0 until 360 step 15) {
                val rad = Math.toRadians((deg + angleOuter * 0.5).toDouble())
                val x1 = cx + (tickInner * cos(rad)).toFloat()
                val y1 = cy + (tickInner * sin(rad)).toFloat()
                val x2 = cx + (tickOuter * cos(rad)).toFloat()
                val y2 = cy + (tickOuter * sin(rad)).toFloat()
                canvas.drawLine(x1, y1, x2, y2, paintOuter)
            }

            val label = when (mode) {
                Mode.IDLE -> "J.A.R.V.I.S."
                Mode.LISTENING -> "LISTENING"
                Mode.SPEAKING -> "ONLINE"
            }
            canvas.drawText(label, cx, cy + 9f, paintText)

            if (isRunning && isAttachedToWindow) {
                postInvalidateOnAnimation()
            }
        }
    }
}
