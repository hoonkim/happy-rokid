package com.hoonkim.happy.rokid.glasses

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var companionView: CompanionView
    private lateinit var bridge: GlassBridge
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val glassesButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_BUTTON_DOWN -> companionView.onPhysicalButtonDown()
                ACTION_BUTTON_UP -> companionView.onPhysicalButtonUp()
                ACTION_BUTTON_CLICK, ACTION_BUTTON_CLICK_V2 -> companionView.onPhysicalButtonClick()
                ACTION_BUTTON_LONG_PRESS -> companionView.onPhysicalButtonLongPress()
                else -> return
            }
            if (isOrderedBroadcast) abortBroadcast()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryFrom(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupFullscreen()

        companionView = CompanionView(this)
        setContentView(companionView)
        bridge = GlassBridge(
            context = this,
            onState = companionView::updateState,
            onFeedback = companionView::showFeedback,
        )
        registerGlassesButtons()
        registerBatteryStatus()
    }

    override fun onStart() {
        super.onStart()
        startBridgeWithPermissions()
    }

    override fun onStop() {
        cancelVoiceControl()
        super.onStop()
    }

    override fun onDestroy() {
        companionView.release()
        bridge.close()
        speechRecognizer?.destroy()
        speechRecognizer = null
        unregisterSafely(glassesButtonReceiver)
        unregisterSafely(batteryReceiver)
        super.onDestroy()
    }

    private fun setupFullscreen() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_PROG_BLUE -> {
            if (event?.repeatCount == 0) startVoiceControl()
            true
        }
        KeyEvent.KEYCODE_BACK -> {
            if (isListening) cancelVoiceControl() else finish()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun onShortAction() {
        val approval = companionView.currentApproval()
        if (approval == null) {
            companionView.showFeedback("현재 권한 요청이 없습니다")
            return
        }
        if (bridge.sendDecision(approval, GlassBridge.DECISION_APPROVE)) {
            companionView.showFeedback("승인을 전송했습니다")
        }
    }

    private fun onLongAction() {
        val approval = companionView.currentApproval()
        if (approval == null) {
            companionView.showFeedback("현재 권한 요청이 없습니다")
            return
        }
        if (bridge.sendDecision(approval, GlassBridge.DECISION_DENY)) {
            companionView.showFeedback("거부를 전송했습니다")
        }
    }

    private fun startVoiceControl() {
        if (isListening) return
        if (companionView.currentApproval() == null) {
            companionView.showFeedback("현재 권한 요청이 없습니다")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            companionView.showFeedback("사용 가능한 음성 인식 서비스가 없습니다")
            return
        }

        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(speechListener)
            speechRecognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "승인 또는 거부")
        }
        isListening = true
        companionView.setListening(true)
        recognizer.startListening(intent)
    }

    private fun cancelVoiceControl() {
        if (!isListening) return
        isListening = false
        speechRecognizer?.cancel()
        companionView.setListening(false)
    }

    private val speechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            isListening = false
            companionView.setListening(false)
            companionView.showFeedback(
                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    "인식하지 못했습니다. 승인 또는 거부라고 말하세요"
                } else {
                    "음성 인식을 다시 시도하세요"
                },
            )
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            companionView.setListening(false)
            handleVoiceCandidates(
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
            )
        }
    }

    private fun handleVoiceCandidates(candidates: List<String>) {
        val normalized = candidates.firstOrNull().orEmpty()
            .lowercase(Locale.KOREAN)
            .replace(Regex("[^가-힣a-z0-9]"), "")
        when (normalized) {
            in APPROVE_PHRASES -> onShortAction()
            in DENY_PHRASES -> onLongAction()
            else -> companionView.showFeedback("승인 또는 거부라고 말하세요")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            AUDIO_PERMISSION_REQUEST -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    startVoiceControl()
                } else {
                    companionView.showFeedback("음성 제어에는 마이크 권한이 필요합니다")
                }
            }
            BLUETOOTH_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults.all {
                        it == PackageManager.PERMISSION_GRANTED
                    }
                ) {
                    bridge.start()
                } else {
                    companionView.showFeedback("휴대전화 연결에는 Nearby devices 권한이 필요합니다")
                }
            }
        }
    }

    private fun startBridgeWithPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            bridge.start()
            return
        }
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            bridge.start()
        } else {
            requestPermissions(permissions, BLUETOOTH_PERMISSION_REQUEST)
        }
    }

    private fun registerGlassesButtons() {
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            addAction(ACTION_BUTTON_DOWN)
            addAction(ACTION_BUTTON_UP)
            addAction(ACTION_BUTTON_CLICK)
            addAction(ACTION_BUTTON_CLICK_V2)
            addAction(ACTION_BUTTON_LONG_PRESS)
        }
        ContextCompat.registerReceiver(
            this,
            glassesButtonReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun registerBatteryStatus() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        updateBatteryFrom(registerReceiver(null, filter))
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun updateBatteryFrom(intent: Intent?) {
        if (intent == null) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percentage = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            null
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        companionView.updateBattery(
            percentage,
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private fun unregisterSafely(receiver: BroadcastReceiver) {
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // The activity can be destroyed before receiver registration completes.
        }
    }

    private inner class CompanionView(context: Context) : View(context) {
        private val brightGreen = Color.rgb(80, 255, 120)
        private val dimGreen = Color.rgb(0, 150, 70)
        private val amber = Color.rgb(255, 185, 40)
        private val red = Color.rgb(255, 85, 75)
        private val cyan = Color.rgb(75, 220, 255)
        private val gray = Color.rgb(125, 145, 135)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var state = CompanionState.waiting()
        private var feedback = ""
        private var feedbackUntil = 0L
        private var listening = false
        private var batteryPercent: Int? = null
        private var charging = false
        private var physicalButtonIsDown = false
        private var physicalButtonLongHandled = false
        private var suppressClickUntil = 0L

        private val clearFeedback = Runnable {
            feedback = ""
            invalidate()
        }
        private val physicalButtonLongPress = Runnable {
            if (physicalButtonIsDown && !physicalButtonLongHandled) {
                physicalButtonLongHandled = true
                onLongAction()
            }
        }

        init {
            setBackgroundColor(Color.BLACK)
            defaultFocusHighlightEnabled = false
            contentDescription = context.getString(R.string.companion_description)
        }

        fun updateState(newState: CompanionState) {
            state = newState
            invalidate()
        }

        fun showFeedback(message: String) {
            feedback = message
            feedbackUntil = SystemClock.elapsedRealtime() + FEEDBACK_DURATION_MS
            removeCallbacks(clearFeedback)
            postDelayed(clearFeedback, FEEDBACK_DURATION_MS)
            invalidate()
        }

        fun setListening(value: Boolean) {
            listening = value
            if (value) showFeedback("듣는 중… ‘승인’ 또는 ‘거부’")
            invalidate()
        }

        fun currentApproval(): ApprovalState? = state.approvals.firstOrNull()

        fun updateBattery(percent: Int?, isCharging: Boolean) {
            batteryPercent = percent
            charging = isCharging
            invalidate()
        }

        fun onPhysicalButtonDown() {
            physicalButtonIsDown = true
            physicalButtonLongHandled = false
            removeCallbacks(physicalButtonLongPress)
            postDelayed(physicalButtonLongPress, PHYSICAL_LONG_PRESS_MS)
        }

        fun onPhysicalButtonUp() {
            if (!physicalButtonIsDown) return
            removeCallbacks(physicalButtonLongPress)
            physicalButtonIsDown = false
            if (!physicalButtonLongHandled) onShortAction()
            suppressClickUntil = SystemClock.elapsedRealtime() + CLICK_BROADCAST_GUARD_MS
        }

        fun onPhysicalButtonClick() {
            val now = SystemClock.elapsedRealtime()
            if (now < suppressClickUntil) return
            removeCallbacks(physicalButtonLongPress)
            physicalButtonIsDown = false
            if (!physicalButtonLongHandled) onShortAction()
            suppressClickUntil = now + CLICK_BROADCAST_GUARD_MS
        }

        fun onPhysicalButtonLongPress() {
            removeCallbacks(physicalButtonLongPress)
            physicalButtonIsDown = false
            if (!physicalButtonLongHandled) onLongAction()
            physicalButtonLongHandled = true
            suppressClickUntil = SystemClock.elapsedRealtime() + LONG_PRESS_RELEASE_GUARD_MS
        }

        fun release() {
            removeCallbacks(clearFeedback)
            removeCallbacks(physicalButtonLongPress)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)

            val unit = width / REFERENCE_WIDTH
            val left = 22f * unit
            val right = width - (22f * unit)
            val center = width / 2f
            val bandTop = height * 0.43f
            val approval = currentApproval()
            val statusColor = when {
                approval != null -> amber
                !state.connected || state.status == "offline" -> red
                state.status == "ready" -> cyan
                else -> brightGreen
            }
            val statusLabel = when {
                !state.connected -> "연결 중"
                approval != null -> "승인 필요"
                state.status == "working" -> "작업 중"
                state.status == "ready" -> "대기"
                else -> "오프라인"
            }

            drawText(canvas, "● $statusLabel", left, bandTop, 15f * unit, statusColor, Paint.Align.LEFT)
            drawText(canvas, batteryLabel(), right, bandTop, 13f * unit, dimGreen, Paint.Align.RIGHT)

            val headline = when {
                !state.connected -> "Happy 앱 연결을 기다리는 중"
                approval != null -> "${approval.tool.ifBlank { "도구" }} 권한 요청"
                state.status == "working" -> "Codex가 작업하고 있습니다"
                state.status == "ready" -> "Codex가 응답을 기다립니다"
                else -> "Codex 세션이 오프라인입니다"
            }
            val detail = when {
                !state.connected -> state.error.ifBlank { "휴대전화에서 Rokid Glasses를 연결하세요" }
                approval != null -> approval.summary
                else -> state.title
            }
            drawWrappedText(canvas, headline, center, bandTop + 42f * unit, 24f * unit, statusColor, 1, right - left, true)
            drawWrappedText(canvas, detail, center, bandTop + 76f * unit, 16f * unit, if (approval != null) amber else brightGreen, 3, right - left)

            if (state.title.isNotBlank() && approval != null) {
                drawText(canvas, state.title, center, height - 74f * unit, 13f * unit, gray)
            }
            val activeFeedback = feedback.takeIf {
                it.isNotBlank() && SystemClock.elapsedRealtime() < feedbackUntil
            }
            val hint = activeFeedback ?: when {
                listening -> "듣는 중… ‘승인’ 또는 ‘거부’"
                approval != null -> "짧게: 승인   길게: 거부   ENTER: 음성"
                else -> "Happy의 Codex 작업을 기다리는 중"
            }
            drawText(
                canvas,
                hint,
                center,
                height - 28f * unit,
                15f * unit,
                if (activeFeedback != null || listening) amber else dimGreen,
                bold = activeFeedback != null,
            )
        }

        private fun batteryLabel(): String = buildString {
            append("BAT ")
            append(batteryPercent?.let { "$it%" } ?: "--%")
            if (charging) append(" +")
        }

        private fun drawWrappedText(
            canvas: Canvas,
            text: String,
            centerX: Float,
            firstBaseline: Float,
            size: Float,
            color: Int,
            maxLines: Int,
            maxWidth: Float,
            bold: Boolean = false,
        ) {
            configurePaint(size, color, bold, Paint.Align.CENTER)
            val lines = wrapText(text.ifBlank { " " }, maxWidth, maxLines)
            val lineHeight = size * 1.28f
            lines.forEachIndexed { index, line ->
                canvas.drawText(line, centerX, firstBaseline + index * lineHeight, paint)
            }
        }

        private fun wrapText(text: String, maxWidth: Float, maxLines: Int): List<String> {
            val normalized = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (normalized.isEmpty()) return listOf("")
            val lines = mutableListOf<String>()
            var start = 0
            while (start < normalized.length && lines.size < maxLines) {
                var end = start + 1
                var lastSpace = -1
                while (end <= normalized.length &&
                    paint.measureText(normalized, start, end) <= maxWidth
                ) {
                    if (normalized[end - 1] == ' ') lastSpace = end - 1
                    end++
                }
                val reachedEnd = end > normalized.length
                var cut = if (reachedEnd) normalized.length else (end - 1).coerceAtLeast(start + 1)
                if (!reachedEnd && lastSpace > start) cut = lastSpace
                var line = normalized.substring(start, cut).trim()
                start = cut
                while (start < normalized.length && normalized[start] == ' ') start++
                if (lines.size == maxLines - 1 && start < normalized.length) {
                    while (line.isNotEmpty() && paint.measureText("$line…") > maxWidth) {
                        line = line.dropLast(1)
                    }
                    line += "…"
                    start = normalized.length
                }
                lines += line
            }
            return lines
        }

        private fun drawText(
            canvas: Canvas,
            text: String,
            x: Float,
            baselineY: Float,
            size: Float,
            color: Int,
            align: Paint.Align = Paint.Align.CENTER,
            bold: Boolean = false,
        ) {
            configurePaint(size, color, bold, align)
            canvas.drawText(text, x, baselineY, paint)
        }

        private fun configurePaint(size: Float, color: Int, bold: Boolean, align: Paint.Align) {
            paint.color = color
            paint.textSize = size
            paint.textAlign = align
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.create(
                Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
        }
    }

    companion object {
        private const val REFERENCE_WIDTH = 480f
        private const val PHYSICAL_LONG_PRESS_MS = 650L
        private const val CLICK_BROADCAST_GUARD_MS = 500L
        private const val LONG_PRESS_RELEASE_GUARD_MS = 750L
        private const val FEEDBACK_DURATION_MS = 3_200L
        private const val AUDIO_PERMISSION_REQUEST = 41
        private const val BLUETOOTH_PERMISSION_REQUEST = 42
        private const val ACTION_BUTTON_DOWN = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
        private const val ACTION_BUTTON_UP = "com.android.action.ACTION_SPRITE_BUTTON_UP"
        private const val ACTION_BUTTON_CLICK = "com.android.action.ACTION_SPRITE_BUTTON_CLICK"
        private const val ACTION_BUTTON_CLICK_V2 = "com.rokid.glass3.action.button.CLICK"
        private const val ACTION_BUTTON_LONG_PRESS = "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
        private val APPROVE_PHRASES = setOf(
            "승인", "승인해", "승인해줘", "승인해주세요", "동의", "동의해", "허용", "허용해", "진행", "진행해",
        )
        private val DENY_PHRASES = setOf(
            "거부", "거부해", "거부해줘", "거부해주세요", "거절", "거절해", "취소", "취소해", "안돼", "아니", "아니요",
        )
    }
}
