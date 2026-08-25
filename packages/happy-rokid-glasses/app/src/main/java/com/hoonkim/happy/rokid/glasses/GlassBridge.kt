package com.hoonkim.happy.rokid.glasses

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback
import com.rokid.security.system.server.IClientCallback
import com.rokid.security.system.server.bluetooth.listener.IClassicBTListener
import com.rokid.security.system.server.message.listener.IMessageListener
import org.json.JSONObject

data class ApprovalState(
    val requestId: String,
    val nonce: String,
    val tool: String,
    val summary: String,
    val expiresAt: Long,
)

data class CompanionState(
    val connected: Boolean,
    val status: String,
    val title: String,
    val approvals: List<ApprovalState>,
    val updatedAt: Long,
    val error: String = "",
) {
    companion object {
        fun waiting(error: String = "") = CompanionState(
            connected = false,
            status = "offline",
            title = "Happy 연결을 기다리는 중",
            approvals = emptyList(),
            updatedAt = 0L,
            error = error,
        )
    }
}

class GlassBridge(
    context: Context,
    private val onState: (CompanionState) -> Unit,
    private val onFeedback: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var state = CompanionState.waiting()
    private var messageListenerAttached = false
    private var bluetoothListenerAttached = false
    @Volatile
    private var pendingDecisionRequestId: String? = null
    @Volatile
    private var currentSessionId = ""
    private val rebind = Runnable { bind() }

    private val clientCallback = object : IClientCallback.Stub() {
        override fun onReady() {
            attachListeners()
            val bluetooth = GlassSdk.getClassicBluetoothService()
            try {
                if (bluetooth?.isConnect != true) bluetooth?.makeDeviceDiscoverable()
            } catch (_: SecurityException) {
                onMain { onFeedback("Glass3 Bluetooth 권한을 허용하세요") }
            }
            val connected = bluetooth?.isConnect == true
            updateState(
                state.copy(
                    connected = connected,
                    error = if (connected) "" else "휴대전화 Bluetooth 연결을 기다리는 중",
                ),
            )
        }
    }

    private val serviceCallback = object : IServiceConnectionCallback {
        override fun onServiceConnected() {
            GlassSdk.registerClient(GLASSES_CLIENT_ID, clientCallback)
        }

        override fun onServiceDisconnected() {
            detachState("Rokid 시스템 서비스 연결이 끊어졌습니다")
        }

        override fun onBindingDied() {
            detachState("Rokid 시스템 서비스를 다시 연결하는 중")
            mainHandler.removeCallbacks(rebind)
            mainHandler.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val bluetoothListener = object : IClassicBTListener.Stub() {
        override fun onClientConnected(device: BluetoothDevice) {
            updateState(state.copy(connected = true, error = ""))
        }

        override fun onClientDisconnected(device: BluetoothDevice) {
            detachState("휴대전화 Bluetooth 연결이 끊어졌습니다")
        }

        override fun onConnectionRejected(device: BluetoothDevice) {
            detachState("휴대전화가 Bluetooth 연결을 거부했습니다")
        }
    }

    private val messageListener = object : IMessageListener.Stub() {
        override fun onTextMessage(msg: String) {
            if (msg.length > MAX_MESSAGE_LENGTH) return
            handleMessage(msg)
        }

        override fun onAudioStream(buffer: ByteArray) = Unit

        override fun onStreamDataReceived(tag: String, data: ByteArray) = Unit
    }

    fun start() {
        mainHandler.removeCallbacks(rebind)
        bind()
    }

    fun close() {
        mainHandler.removeCallbacks(rebind)
        try {
            if (messageListenerAttached) {
                GlassSdk.getGlassMessageService()?.removeMessageListener(messageListener)
            }
            if (bluetoothListenerAttached) {
                GlassSdk.getClassicBluetoothService()
                    ?.removeBlueToothServerListener(bluetoothListener)
            }
        } finally {
            messageListenerAttached = false
            bluetoothListenerAttached = false
            GlassSdk.release()
        }
    }

    fun sendDecision(approval: ApprovalState, decision: String): Boolean {
        if (decision != DECISION_APPROVE && decision != DECISION_DENY) return false
        if (!state.connected || System.currentTimeMillis() >= approval.expiresAt) {
            onMain { onFeedback("요청이 만료되었거나 휴대전화가 연결되지 않았습니다") }
            return false
        }
        if (pendingDecisionRequestId != null) {
            onMain { onFeedback("이전 응답을 확인하는 중입니다") }
            return false
        }

        val payload = JSONObject()
            .put("v", PROTOCOL_VERSION)
            .put("type", "decision")
            .put("sessionId", currentSessionId)
            .put("requestId", approval.requestId)
            .put("nonce", approval.nonce)
            .put("decision", decision)
            .put("sentAt", System.currentTimeMillis())
            .toString()

        if (payload.length > MAX_MESSAGE_LENGTH) return false
        return try {
            val service = GlassSdk.getGlassMessageService() ?: return false
            pendingDecisionRequestId = approval.requestId
            service.sendTextMessageByClassicBTWithClient(payload, PHONE_CLIENT_ID)
            true
        } catch (_: Exception) {
            pendingDecisionRequestId = null
            onMain { onFeedback("응답을 전송하지 못했습니다") }
            false
        }
    }

    private fun bind() {
        if (GlassSdk.isReady()) {
            GlassSdk.registerClient(GLASSES_CLIENT_ID, clientCallback)
        } else {
            GlassSdk.bindSecurityService(appContext, serviceCallback)
        }
    }

    private fun attachListeners() {
        if (!messageListenerAttached) {
            GlassSdk.getGlassMessageService()?.setMessageListener(messageListener)
            messageListenerAttached = true
        }
        if (!bluetoothListenerAttached) {
            GlassSdk.getClassicBluetoothService()?.setClassicBTListener(bluetoothListener)
            bluetoothListenerAttached = true
        }
    }

    private fun handleMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            if (json.optInt("v") != PROTOCOL_VERSION) return
            when (json.optString("type")) {
                "session_state" -> handleSessionState(json)
                "decision_ack" -> handleDecisionAck(json)
            }
        } catch (_: Exception) {
            onMain { onFeedback("Happy 메시지를 읽지 못했습니다") }
        }
    }

    private fun handleSessionState(json: JSONObject) {
        val session = json.optJSONObject("session") ?: return
        val sessionId = session.optString("id").take(MAX_ID_LENGTH)
        if (sessionId.isBlank()) return
        val approvalsJson = json.optJSONArray("approvals")
        val approvals = buildList {
            if (approvalsJson != null) {
                for (index in 0 until minOf(approvalsJson.length(), MAX_APPROVALS)) {
                    val item = approvalsJson.optJSONObject(index) ?: continue
                    val requestId = item.optString("requestId").take(MAX_ID_LENGTH)
                    val nonce = item.optString("nonce").take(MAX_ID_LENGTH)
                    val expiresAt = item.optLong("expiresAt", 0L)
                    if (requestId.isBlank() || nonce.isBlank() || expiresAt <= 0L) continue
                    add(
                        ApprovalState(
                            requestId = requestId,
                            nonce = nonce,
                            tool = safeText(item.optString("tool"), 80),
                            summary = safeText(item.optString("summary"), 320),
                            expiresAt = expiresAt,
                        ),
                    )
                }
            }
        }

        currentSessionId = sessionId
        if (pendingDecisionRequestId != null && approvals.none {
                it.requestId == pendingDecisionRequestId
            }
        ) {
            pendingDecisionRequestId = null
        }
        updateState(
            CompanionState(
                connected = true,
                status = session.optString("status", "ready").take(40),
                title = safeText(session.optString("title", "Codex"), 80),
                approvals = approvals,
                updatedAt = session.optLong("updatedAt", json.optLong("sentAt", 0L)),
            ),
        )
    }

    private fun handleDecisionAck(json: JSONObject) {
        val requestId = json.optString("requestId")
        if (requestId.isBlank() || requestId != pendingDecisionRequestId) return
        pendingDecisionRequestId = null
        val accepted = json.optBoolean("accepted", false)
        val reason = safeText(json.optString("reason"), 120)
        onMain {
            onFeedback(
                if (accepted) "Happy가 응답을 처리했습니다"
                else reason.ifBlank { "Happy가 응답을 거부했습니다" },
            )
        }
    }

    private fun detachState(message: String) {
        pendingDecisionRequestId = null
        updateState(state.copy(connected = false, error = message))
    }

    private fun updateState(newState: CompanionState) {
        state = newState
        onMain { onState(newState) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun safeText(value: String, maxLength: Int): String = value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxLength)

    companion object {
        const val DECISION_APPROVE = "approve"
        const val DECISION_DENY = "deny"
        private const val PROTOCOL_VERSION = 1
        private const val PHONE_CLIENT_ID = "HappyRokidPhone"
        private const val GLASSES_CLIENT_ID = "HappyRokidGlasses"
        private const val MAX_MESSAGE_LENGTH = 4096
        private const val MAX_ID_LENGTH = 300
        private const val MAX_APPROVALS = 5
        private const val REBIND_DELAY_MS = 4_000L

    }
}
