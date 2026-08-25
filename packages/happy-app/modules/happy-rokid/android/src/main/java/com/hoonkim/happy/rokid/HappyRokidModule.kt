package com.hoonkim.happy.rokid

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.os.bundleOf
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import com.rokid.cxr.session.AuthResult
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.RokidAppStatus
import com.rokid.cxr.session.SessionErrorCode
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONArray
import org.json.JSONObject

class HappyRokidModule : Module() {
    private var manager: CxrSessionManager? = null
    private var link: CXRLink? = null
    private var authorizationPending = false
    private var linkConnected = false
    private var glassesConnected = false
    private var viewOpen = false
    private var openScheduled = false
    private var openAttempts = 0
    private var latestSnapshot = DisplaySnapshot.offline()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun definition() = ModuleDefinition {
        Name("HappyRokid")
        Events("onRokidState")

        Function("initialize") {
            val context = appContext.reactContext?.applicationContext ?: return@Function false
            val sessionManager = getManager(context)

            when (val status = sessionManager.checkRokidAppCompatibility(context)) {
                is RokidAppStatus.NotInstalled -> {
                    emitState("unavailable", "Hi Rokid 앱을 먼저 설치하세요")
                    return@Function false
                }
                is RokidAppStatus.VersionTooLow -> {
                    emitState(
                        "error",
                        "Hi Rokid 업데이트가 필요합니다 (${status.installedVersion} → ${status.minimumVersion})",
                    )
                    return@Function false
                }
                is RokidAppStatus.Compatible -> Unit
            }

            val prefs = preferences(context)
            val wasAuthorized = prefs.getBoolean(PREF_AUTHORIZED, false) || prefs.contains(PREF_TOKEN)
            if (!wasAuthorized) {
                emitState("authorization_required", "Hi Rokid에서 Happy 연결을 승인하세요")
                return@Function true
            }

            val activity = appContext.currentActivity ?: run {
                emitState("authorization_required", "Happy를 열고 Hi Rokid 연결을 확인하세요")
                return@Function false
            }
            refreshAuthorization(activity, showPrompt = false)
        }

        Function("authorize") {
            val activity = appContext.currentActivity ?: return@Function false
            val sessionManager = getManager(activity.applicationContext)
            if (!sessionManager.isRokidAppInstalled(activity)) {
                emitState("unavailable", "Hi Rokid 앱을 먼저 설치하세요")
                return@Function false
            }

            refreshAuthorization(activity, showPrompt = true)
        }

        OnActivityResult { _, payload ->
            if (authorizationPending) {
                manager?.parseAuthorizationResult(payload.resultCode, payload.data)
            }
        }

        Function("disconnect") {
            disconnectLink()
            emitState("disconnected", "Rokid Glasses 표시를 종료했습니다")
            true
        }

        Function("send") { message: String ->
            if (message.length > MAX_MESSAGE_LENGTH) return@Function false
            val parsed = DisplaySnapshot.parse(message) ?: return@Function false
            latestSnapshot = parsed

            val activeLink = link ?: return@Function false
            if (!viewOpen) return@Function false
            val accepted = activeLink.customViewUpdate(renderUpdate(parsed))
            if (!accepted) {
                emitState("error", "안경 화면 갱신 요청을 보내지 못했습니다")
            }
            accepted
        }

        OnDestroy {
            disconnectLink()
        }
    }

    private fun getManager(context: Context): CxrSessionManager {
        return manager ?: CxrSessionManager.Companion.getInstance(context).also { manager = it }
    }

    private fun refreshAuthorization(activity: Activity, showPrompt: Boolean): Boolean {
        val sessionManager = getManager(activity.applicationContext)
        authorizationPending = true
        emitState(
            "authorizing",
            if (showPrompt) {
                "Hi Rokid 승인 화면을 확인하세요"
            } else {
                "Hi Rokid 연결 정보를 확인하는 중"
            },
        )
        // Re-querying Hi Rokid also restores the SDK's global-app selection,
        // which CXR-L 1.1.1 otherwise loses when Happy's process restarts.
        // CUSTOM_VIEW requests no camera or microphone access.
        sessionManager.requestAuthorization(activity, emptyList()) { result ->
            authorizationPending = false
            handleAuthorizationResult(activity.applicationContext, result)
        }
        return true
    }

    private fun handleAuthorizationResult(context: Context, result: AuthResult) {
        if (!result.isSuccess || result.token.isNullOrBlank()) {
            preferences(context).edit()
                .putBoolean(PREF_AUTHORIZED, false)
                .remove(PREF_TOKEN)
                .apply()
            emitState(
                "authorization_required",
                if (result.errorCode == SessionErrorCode.OPERATION_CANCELLED) {
                    "Hi Rokid 연결 승인이 취소되었습니다"
                } else {
                    "Hi Rokid 연결 승인에 실패했습니다"
                },
            )
            return
        }

        preferences(context).edit()
            .putBoolean(PREF_AUTHORIZED, true)
            .remove(PREF_TOKEN)
            .apply()
        connect(result.token!!)
    }

    private fun connect(token: String) {
        val context = appContext.reactContext?.applicationContext ?: return
        if (link != null) {
            emitState(
                if (viewOpen) "connected" else "connecting",
                if (viewOpen) {
                    "Rokid Glasses에 Codex 상태를 표시하고 있습니다"
                } else {
                    "Hi Rokid를 통해 안경에 연결하는 중"
                },
            )
            return
        }

        val created = CXRLink(context)
        val configured = created.configCXRSession(
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMVIEW),
        )
        if (!configured) {
            emitState("error", "Hi Rokid 화면 세션을 준비하지 못했습니다")
            return
        }

        created.setCXRCustomViewCbk(customViewCallback(created))
        created.setCXRLinkCbk(linkCallback(created))
        resetConnectionState()
        link = created
        emitState("connecting", "Hi Rokid를 통해 안경에 연결하는 중")
        if (!created.connect(token)) {
            link = null
            resetConnectionState()
            emitState("error", "Hi Rokid 연결 요청을 시작하지 못했습니다")
        }
    }

    private fun linkCallback(target: CXRLink) = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            mainHandler.post {
                if (link !== target) return@post
                linkConnected = connected
                if (connected) {
                    scheduleViewOpen(target)
                } else {
                    viewOpen = false
                    emitState("disconnected", "Hi Rokid 연결이 종료되었습니다")
                }
            }
        }

        override fun onGlassBtConnected(connected: Boolean) {
            mainHandler.post {
                if (link !== target) return@post
                glassesConnected = connected
                if (connected) {
                    scheduleViewOpen(target)
                } else {
                    viewOpen = false
                    openAttempts = 0
                    emitState("disconnected", "Hi Rokid에서 Rokid Glasses 연결을 확인하세요")
                }
            }
        }

        override fun onGlassDeviceInfo(info: GlassInfo) = Unit

        override fun onGlassWearingStatus(wearing: Boolean) = Unit

        override fun onGlassAiAssistStart() {
            mainHandler.post {
                if (link === target) {
                    emitState("paused", "Rokid AI 사용 중에는 Codex 표시가 잠시 멈춥니다")
                }
            }
        }

        override fun onGlassAiAssistStop() {
            mainHandler.post {
                if (link !== target) return@post
                if (target.customViewIsOpen()) {
                    markViewOpen(target)
                    target.customViewUpdate(renderUpdate(latestSnapshot))
                } else {
                    viewOpen = false
                    scheduleViewOpen(target)
                }
            }
        }

        override fun onGlassAiInterrupt(interrupted: Boolean) {
            if (interrupted) onGlassAiAssistStart() else onGlassAiAssistStop()
        }

        override fun onGlassLauncherResume() {
            mainHandler.post {
                if (link !== target) return@post
                if (!target.customViewIsOpen()) {
                    viewOpen = false
                    scheduleViewOpen(target)
                }
            }
        }
    }

    private fun customViewCallback(target: CXRLink) = object : ICustomViewCbk {
        override fun onCustomViewOpened() {
            mainHandler.post { markViewOpen(target) }
        }

        override fun onCustomViewUpdated() = Unit

        override fun onCustomViewClosed() {
            mainHandler.post {
                if (link === target) viewOpen = false
            }
        }

        override fun onCustomViewIconsSent() = Unit

        override fun onCustomViewError(code: Int, message: String?) {
            mainHandler.post {
                if (link !== target) return@post
                viewOpen = false
                emitState("error", "안경 화면을 열지 못했습니다 ($code)")
            }
        }
    }

    private fun scheduleViewOpen(target: CXRLink) {
        if (link !== target || !linkConnected || !glassesConnected || viewOpen || openScheduled) return
        openScheduled = true

        // CXR-L announces the link and Bluetooth state while its binder callback
        // registrations are still being completed. Deferring the open avoids
        // losing onCustomViewOpened on Hi Rokid 1.12 / client-l 1.1.1.
        mainHandler.postDelayed({
            openScheduled = false
            if (link !== target || !linkConnected || !glassesConnected || viewOpen) return@postDelayed
            openView(target)
        }, VIEW_OPEN_DELAY_MS)
    }

    private fun openView(target: CXRLink) {
        if (link !== target) return
        openAttempts += 1
        if (!target.customViewOpen(renderFull(latestSnapshot))) {
            emitState("error", "안경 화면 열기 요청을 보내지 못했습니다")
            return
        }

        mainHandler.postDelayed({
            if (link !== target || viewOpen) return@postDelayed
            if (target.customViewIsOpen()) {
                markViewOpen(target)
            } else if (openAttempts < MAX_VIEW_OPEN_ATTEMPTS) {
                scheduleViewOpen(target)
            } else {
                emitState("error", "안경 화면 응답이 없습니다. Hi Rokid 연결을 확인하세요")
            }
        }, VIEW_OPEN_VERIFY_DELAY_MS)
    }

    private fun markViewOpen(target: CXRLink) {
        if (link !== target) return
        viewOpen = true
        openAttempts = 0
        emitState("connected", "Rokid Glasses에 Codex 상태를 표시하고 있습니다")
    }

    private fun resetConnectionState() {
        linkConnected = false
        glassesConnected = false
        viewOpen = false
        openScheduled = false
        openAttempts = 0
    }

    private fun disconnectLink() {
        val activeLink = link
        link = null
        resetConnectionState()
        runCatching { activeLink?.customViewClose() }
        runCatching { activeLink?.disconnect() }
    }

    private fun renderFull(snapshot: DisplaySnapshot): String {
        val children = JSONArray()
            .put(textNode("status", snapshot.statusLine, "18sp", bold = true, marginTop = null))
            .put(textNode("title", snapshot.title, "16sp", bold = true, marginTop = "14dp"))
            .put(textNode("detail", snapshot.detail, "13sp", bold = false, marginTop = "14dp"))
            .put(textNode("action", snapshot.action, "12sp", bold = false, marginTop = "18dp"))
            .put(textNode("footer", "Happy · Hi Rokid CXR-L", "10sp", bold = false, marginTop = "18dp"))

        return JSONObject()
            .put("type", "LinearLayout")
            .put(
                "props",
                JSONObject()
                    .put("id", "root")
                    .put("layout_width", "match_parent")
                    .put("layout_height", "match_parent")
                    .put("orientation", "vertical")
                    .put("gravity", "start")
                    .put("backgroundColor", "#FF000000")
                    .put("paddingStart", "22dp")
                    .put("paddingEnd", "22dp")
                    .put("paddingTop", "72dp")
                    .put("paddingBottom", "20dp"),
            )
            .put("children", children)
            .toString()
    }

    private fun renderUpdate(snapshot: DisplaySnapshot): String {
        return JSONArray()
            .put(updateNode("status", snapshot.statusLine))
            .put(updateNode("title", snapshot.title))
            .put(updateNode("detail", snapshot.detail))
            .put(updateNode("action", snapshot.action))
            .toString()
    }

    private fun textNode(
        id: String,
        text: String,
        textSize: String,
        bold: Boolean,
        marginTop: String?,
    ): JSONObject {
        val props = JSONObject()
            .put("id", id)
            .put("layout_width", "match_parent")
            .put("layout_height", "wrap_content")
            .put("text", text)
            .put("textColor", "#00FF00")
            .put("textSize", textSize)
            .put("gravity", "start")
        if (bold) props.put("textStyle", "bold")
        if (marginTop != null) props.put("marginTop", marginTop)
        return JSONObject().put("type", "TextView").put("props", props)
    }

    private fun updateNode(id: String, text: String): JSONObject {
        return JSONObject()
            .put("action", "update")
            .put("id", id)
            .put("props", JSONObject().put("text", text))
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun emitState(state: String, message: String) {
        sendEvent("onRokidState", bundleOf("state" to state, "message" to message))
    }

    private data class DisplaySnapshot(
        val statusLine: String,
        val title: String,
        val detail: String,
        val action: String,
    ) {
        companion object {
            fun offline() = DisplaySnapshot(
                statusLine = "CODEX · 오프라인",
                title = "Codex 세션 없음",
                detail = "연결된 Codex 작업을 기다리고 있습니다.",
                action = "휴대전화의 Happy 앱을 확인하세요.",
            )

            fun parse(raw: String): DisplaySnapshot? {
                return try {
                    val root = JSONObject(raw)
                    if (root.optInt("v") != 1 || root.optString("type") != "session_state") {
                        return null
                    }
                    val session = root.getJSONObject("session")
                    val title = session.optString("title", "Codex").bounded(90)
                    val status = session.optString("status", "offline")
                    val approvals = root.optJSONArray("approvals") ?: JSONArray()
                    val approval = approvals.optJSONObject(0)

                    if (approval != null) {
                        val tool = approval.optString("tool", "권한 요청").bounded(50)
                        val summary = approval.optString("summary", "내용을 확인하세요").bounded(180)
                        DisplaySnapshot(
                            statusLine = "CODEX · 승인 필요",
                            title = title,
                            detail = "$tool\n$summary",
                            action = "현재는 휴대전화에서 승인 또는 거부하세요.",
                        )
                    } else {
                        val statusLine = when (status) {
                            "working" -> "CODEX · 작업 중"
                            "ready" -> "CODEX · 대기"
                            "permission_required" -> "CODEX · 승인 필요"
                            else -> "CODEX · 오프라인"
                        }
                        val detail = when (status) {
                            "working" -> "Codex가 작업을 진행하고 있습니다."
                            "ready" -> "새 요청을 기다리는 중입니다."
                            "permission_required" -> "휴대전화에서 권한 요청을 확인하세요."
                            else -> "Codex 세션이 오프라인입니다."
                        }
                        DisplaySnapshot(
                            statusLine = statusLine,
                            title = title,
                            detail = detail,
                            action = "진행 상태는 자동으로 갱신됩니다.",
                        )
                    }
                } catch (_: Exception) {
                    null
                }
            }

            private fun String.bounded(limit: Int): String {
                val compact = replace(Regex("[\\r\\n\\t]+"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                return if (compact.length <= limit) compact else compact.take(limit - 1) + "…"
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "happy_rokid_cxrl"
        private const val PREF_AUTHORIZED = "authorization_confirmed"
        private const val PREF_TOKEN = "authorization_token"
        private const val MAX_MESSAGE_LENGTH = 4096
        private const val MAX_VIEW_OPEN_ATTEMPTS = 2
        private const val VIEW_OPEN_DELAY_MS = 600L
        private const val VIEW_OPEN_VERIFY_DELAY_MS = 1_200L
    }
}
