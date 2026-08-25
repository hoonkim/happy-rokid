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
            if (!startRelayService(activity.applicationContext)) return@Function false
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
            appContext.reactContext?.applicationContext?.let(HappyRokidService::stop)
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
            HappyRokidService.stop(context)
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
        if (!startRelayService(context)) return
        connect(result.token!!)
    }

    private fun connect(token: String) {
        val context = appContext.reactContext?.applicationContext ?: return
        if (link != null) {
            emitState(
                if (viewOpen) "connected" else "connecting",
                if (viewOpen) {
                    "Rokid Glasses에 Happy 세션을 표시하고 있습니다"
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
                    emitState("paused", "Rokid AI 사용 중에는 Happy 표시가 잠시 멈춥니다")
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
        emitState("connected", "Rokid Glasses에 Happy 세션을 표시하고 있습니다")
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

    private fun startRelayService(context: Context): Boolean {
        return runCatching {
            HappyRokidService.start(context)
            true
        }.getOrElse {
            emitState("error", "화면이 꺼진 동안 Rokid 중계를 유지할 수 없습니다")
            false
        }
    }

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
                statusLine = "HAPPY · 오프라인",
                title = "Happy 세션 없음",
                detail = "연결된 Happy 작업을 기다리고 있습니다.",
                action = "휴대전화의 Happy 앱을 확인하세요.",
            )

            fun parse(raw: String): DisplaySnapshot? {
                return try {
                    val root = JSONObject(raw)
                    if (root.optInt("v") != 2 || root.optString("type") != "session_state") {
                        return null
                    }
                    val session = root.getJSONObject("session")
                    val title = session.optString("title", "Happy 세션").bounded(72)
                    val agent = session.optString("agent", "Happy").bounded(24)
                    val status = session.optString("status", "offline")
                    val pinned = session.optBoolean("pinned", false)
                    val sessionCount = root.optInt("sessionCount", 0)
                    val activeSessionCount = root.optInt("activeSessionCount", 0)
                    val approvalCount = root.optInt("approvalCount", 0)
                    val sessions = root.optJSONArray("sessions") ?: JSONArray()
                    val approvals = root.optJSONArray("approvals") ?: JSONArray()
                    val approval = approvals.optJSONObject(0)

                    val statusLine = when {
                        approvalCount > 0 -> "HAPPY · 승인 필요 $approvalCount"
                        activeSessionCount > 0 -> "HAPPY · 실행 중 $activeSessionCount"
                        else -> "HAPPY · 오프라인"
                    }
                    val titleLine = buildString {
                        append(agent)
                        append(" · ")
                        append(title)
                        if (pinned) append(" · 고정")
                    }.bounded(105)
                    val rows = mutableListOf<String>()
                    for (index in 0 until minOf(sessions.length(), 3)) {
                        val item = sessions.optJSONObject(index) ?: continue
                        val itemStatus = item.optString("status", "offline")
                        val itemAgent = item.optString("agent", "Happy").bounded(18)
                        val itemTitle = item.optString("title", "Happy 세션").bounded(44)
                        rows += "${statusMarker(itemStatus)} $itemAgent · $itemTitle · ${statusLabel(itemStatus)}"
                    }
                    val hiddenCount = (sessionCount - rows.size).coerceAtLeast(0)
                    if (hiddenCount > 0) rows += "외 ${hiddenCount}개 세션"
                    val detail = if (rows.isEmpty()) {
                        "연결된 Happy 작업을 기다리고 있습니다."
                    } else {
                        rows.joinToString("\n").bounded(260)
                    }
                    val action = if (approval != null) {
                        val approvalAgent = approval.optString("agent", "Happy").bounded(18)
                        val approvalSession = approval.optString("sessionTitle", "Happy 세션").bounded(46)
                        val tool = approval.optString("tool", "권한 요청").bounded(42)
                        val summary = approval.optString("summary", "내용을 확인하세요").bounded(120)
                        "$approvalAgent · $approvalSession\n$tool · $summary\n휴대전화에서 승인 또는 거부하세요."
                            .bounded(230)
                    } else {
                        when (status) {
                            "working" -> "대표 세션이 작업을 진행하고 있습니다."
                            "ready" -> "대표 세션이 새 요청을 기다리고 있습니다."
                            "permission_required" -> "휴대전화에서 권한 요청을 확인하세요."
                            else -> "대표 세션이 오프라인입니다."
                        }
                    }
                    DisplaySnapshot(statusLine, titleLine, detail, action)
                } catch (_: Exception) {
                    null
                }
            }

            private fun statusMarker(status: String): String = when (status) {
                "permission_required" -> "!"
                "working" -> "●"
                "ready" -> "○"
                else -> "×"
            }

            private fun statusLabel(status: String): String = when (status) {
                "permission_required" -> "승인 필요"
                "working" -> "작업 중"
                "ready" -> "대기"
                else -> "오프라인"
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
        private const val MAX_MESSAGE_LENGTH = 16_384
        private const val MAX_VIEW_OPEN_ATTEMPTS = 2
        private const val VIEW_OPEN_DELAY_MS = 600L
        private const val VIEW_OPEN_VERIFY_DELAY_MS = 1_200L
    }
}
