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
    private var answerPageIndex = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val answerPageRunnable = object : Runnable {
        override fun run() {
            val activeLink = link ?: return
            if (!viewOpen || latestSnapshot.actionPages.size <= 1) return
            answerPageIndex = (answerPageIndex + 1) % latestSnapshot.actionPages.size
            activeLink.customViewUpdate(renderUpdate(latestSnapshot))
            mainHandler.postDelayed(this, ANSWER_PAGE_INTERVAL_MS)
        }
    }

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
            if (parsed.actionPages != latestSnapshot.actionPages) {
                answerPageIndex = 0
            }
            latestSnapshot = parsed

            val activeLink = link ?: return@Function false
            if (!viewOpen) return@Function false
            val accepted = activeLink.customViewUpdate(renderUpdate(parsed))
            if (!accepted) {
                emitState("error", "안경 화면 갱신 요청을 보내지 못했습니다")
            } else {
                scheduleAnswerPagination()
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
        scheduleAnswerPagination()
        emitState("connected", "Rokid Glasses에 Happy 세션을 표시하고 있습니다")
    }

    private fun resetConnectionState() {
        stopAnswerPagination()
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
            .put(textNode("status", snapshot.statusLine, "16sp", bold = true, marginTop = null))
            .put(textNode("title", snapshot.title, "11sp", bold = false, marginTop = "8dp"))
            .put(textNode("detail", snapshot.detailAt(answerPageIndex), "12sp", bold = false, marginTop = "10dp"))
            .put(textNode("action", snapshot.actionAt(answerPageIndex), "11sp", bold = false, marginTop = "12dp"))

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
                    .put("paddingStart", "18dp")
                    .put("paddingEnd", "18dp")
                    .put("paddingTop", "72dp")
                    .put("paddingBottom", "12dp"),
            )
            .put("children", children)
            .toString()
    }

    private fun renderUpdate(snapshot: DisplaySnapshot): String {
        return JSONArray()
            .put(updateNode("status", snapshot.statusLine))
            .put(updateNode("title", snapshot.title))
            .put(updateNode("detail", snapshot.detailAt(answerPageIndex)))
            .put(updateNode("action", snapshot.actionAt(answerPageIndex)))
            .toString()
    }

    private fun scheduleAnswerPagination() {
        stopAnswerPagination()
        if (viewOpen && latestSnapshot.actionPages.size > 1) {
            mainHandler.postDelayed(answerPageRunnable, ANSWER_PAGE_INTERVAL_MS)
        }
    }

    private fun stopAnswerPagination() {
        mainHandler.removeCallbacks(answerPageRunnable)
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
        val actionPages: List<String>,
        val pinned: Boolean,
    ) {
        private fun pageAt(index: Int): String = actionPages.getOrElse(index) {
            actionPages.firstOrNull().orEmpty()
        }

        fun detailAt(index: Int): String = if (pinned) pageAt(index) else detail

        fun actionAt(index: Int): String = if (pinned) "" else pageAt(index)

        companion object {
            fun offline() = DisplaySnapshot(
                statusLine = "HAPPY · 오프라인",
                title = "●작업  ○대기  !승인  ×오프라인",
                detail = "연결된 Happy 작업을 기다리고 있습니다.",
                actionPages = listOf(""),
                pinned = false,
            )

            fun parse(raw: String): DisplaySnapshot? {
                return try {
                    val root = JSONObject(raw)
                    if (root.optInt("v") != 2 || root.optString("type") != "session_state") {
                        return null
                    }
                    val session = root.getJSONObject("session")
                    val title = session.optString("title", "Happy 세션").boundedSingleLine(72)
                    val agent = session.optString("agent", "Happy").boundedSingleLine(24)
                    val latestResponse = session.optString("latestResponse", "")
                        .boundedMultiline(520)
                    val status = session.optString("status", "offline")
                    val pinned = session.optBoolean("pinned", false)
                    val sessionCount = root.optInt("sessionCount", 0)
                    val activeSessionCount = root.optInt("activeSessionCount", 0)
                    val approvalCount = root.optInt("approvalCount", 0)
                    val sessions = root.optJSONArray("sessions") ?: JSONArray()
                    val approvals = root.optJSONArray("approvals") ?: JSONArray()
                    val approval = if (pinned) {
                        approvalForSession(approvals, session.optString("id"))
                    } else {
                        approvals.optJSONObject(0)
                    }

                    val statusLine = if (pinned) {
                        "${statusMarker(status)} $agent · $title"
                    } else {
                        "HAPPY · ${sessionCount}개 · 실행 $activeSessionCount · 승인 $approvalCount"
                    }
                    val titleLine = if (pinned) "" else "●작업  ○대기  !승인  ×오프라인"
                    val rows = mutableListOf<String>()
                    for (index in 0 until minOf(sessions.length(), 8)) {
                        val item = sessions.optJSONObject(index) ?: continue
                        val itemStatus = item.optString("status", "offline")
                        val itemAgent = item.optString("agent", "Happy").boundedSingleLine(12)
                        val itemTitle = item.optString("title", "Happy 세션").boundedSingleLine(28)
                        rows += "${statusMarker(itemStatus)} $itemAgent · $itemTitle"
                    }
                    val hiddenCount = (sessionCount - rows.size).coerceAtLeast(0)
                    if (hiddenCount > 0) rows += "외 ${hiddenCount}개 세션"
                    val detail = if (pinned) {
                        ""
                    } else if (rows.isEmpty()) {
                        "연결된 Happy 작업을 기다리고 있습니다."
                    } else {
                        rows.joinToString("\n").boundedMultiline(800)
                    }
                    val actionPages = if (approval != null) {
                        val approvalAgent = approval.optString("agent", "Happy").boundedSingleLine(18)
                        val approvalSession = approval.optString("sessionTitle", "Happy 세션").boundedSingleLine(46)
                        val tool = approval.optString("tool", "권한 요청").boundedSingleLine(42)
                        val summary = approval.optString("summary", "내용을 확인하세요").boundedSingleLine(120)
                        listOf(
                            "$approvalAgent · $approvalSession\n$tool · $summary\n휴대전화에서 승인 또는 거부하세요."
                                .boundedMultiline(230),
                        )
                    } else if (pinned && latestResponse.isNotBlank()) {
                        val responsePages = latestResponse.paginate(ANSWER_PAGE_CHARACTER_LIMIT)
                        responsePages.mapIndexed { index, page ->
                            val label = if (responsePages.size > 1) {
                                "최신 답변 ${index + 1}/${responsePages.size}"
                            } else {
                                "최신 답변"
                            }
                            "$label\n$page"
                        }
                    } else if (pinned) {
                        listOf(when (status) {
                            "working" -> "작업 중\n답변을 작성하고 있습니다."
                            "ready" -> "최신 답변\n아직 표시할 답변이 없습니다."
                            "permission_required" -> "승인 필요\n휴대전화에서 권한 요청을 확인하세요."
                            else -> "오프라인\n마지막 답변을 불러오지 못했습니다."
                        })
                    } else {
                        listOf("")
                    }
                    DisplaySnapshot(statusLine, titleLine, detail, actionPages, pinned)
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

            private fun approvalForSession(approvals: JSONArray, sessionId: String): JSONObject? {
                for (index in 0 until approvals.length()) {
                    val approval = approvals.optJSONObject(index) ?: continue
                    if (approval.optString("sessionId") == sessionId) return approval
                }
                return null
            }

            private fun String.boundedSingleLine(limit: Int): String {
                val compact = replace(Regex("[\\r\\n\\t]+"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                return if (compact.length <= limit) compact else compact.take(limit - 1) + "…"
            }

            private fun String.boundedMultiline(limit: Int): String {
                val compact = replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .split('\n')
                    .map { line -> line.replace(Regex("[\\t ]+"), " ").trim() }
                    .filter { line -> line.isNotEmpty() }
                    .joinToString("\n")
                return if (compact.length <= limit) compact else compact.take(limit - 1) + "…"
            }

            private fun String.paginate(limit: Int): List<String> {
                val value = trim()
                if (value.isEmpty()) return emptyList()
                val pages = mutableListOf<String>()
                var start = 0
                while (start < value.length) {
                    var end = minOf(start + limit, value.length)
                    if (end < value.length) {
                        val minimumBreak = start + limit / 2
                        for (index in end - 1 downTo minimumBreak) {
                            val character = value[index]
                            if (character.isWhitespace() || character in ".!?。！？") {
                                end = index + 1
                                break
                            }
                        }
                    }
                    value.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(pages::add)
                    start = end
                    while (start < value.length && value[start].isWhitespace()) start += 1
                }
                return pages.ifEmpty { listOf(value) }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "happy_rokid_cxrl"
        private const val PREF_AUTHORIZED = "authorization_confirmed"
        private const val PREF_TOKEN = "authorization_token"
        private const val MAX_MESSAGE_LENGTH = 16_384
        private const val ANSWER_PAGE_CHARACTER_LIMIT = 180
        private const val ANSWER_PAGE_INTERVAL_MS = 12_000L
        private const val MAX_VIEW_OPEN_ATTEMPTS = 2
        private const val VIEW_OPEN_DELAY_MS = 600L
        private const val VIEW_OPEN_VERIFY_DELAY_MS = 1_200L
    }
}
