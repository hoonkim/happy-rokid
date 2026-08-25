package com.hoonkim.happy.rokid

import android.app.Activity
import android.content.Context
import androidx.core.os.bundleOf
import com.rokid.cxr.session.AuthResult
import com.rokid.cxr.session.CloseReason
import com.rokid.cxr.session.CxrSession
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.ISessionLifecycleCbk
import com.rokid.cxr.session.PausedReason
import com.rokid.cxr.session.RokidAppStatus
import com.rokid.cxr.session.SessionConfig
import com.rokid.cxr.session.SessionErrorCode
import com.rokid.cxr.session.SessionState
import com.rokid.cxr.session.SessionType
import com.rokid.cxr.session.TerminatingReason
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONArray
import org.json.JSONObject

class HappyRokidModule : Module() {
    private var manager: CxrSessionManager? = null
    private var session: CxrSession? = null
    private var authorizationPending = false
    private var latestSnapshot = DisplaySnapshot.offline()

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
            session?.close()
            session = null
            emitState("disconnected", "Rokid Glasses 표시를 종료했습니다")
            true
        }

        Function("send") { message: String ->
            if (message.length > MAX_MESSAGE_LENGTH) return@Function false
            val parsed = DisplaySnapshot.parse(message) ?: return@Function false
            latestSnapshot = parsed

            val activeSession = session ?: return@Function false
            if (activeSession.getState() != SessionState.Started) return@Function false
            val result = activeSession.customViewUpdate(renderUpdate(parsed))
            if (!result.isSuccess) {
                emitState("error", "안경 화면 갱신 실패: ${result.code.name}")
            }
            result.isSuccess
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
        val sessionManager = getManager(context)
        val current = session
        if (current != null && current.getState() != SessionState.Idle) {
            emitState(
                if (current.getState() == SessionState.Started) "connected" else "connecting",
                if (current.getState() == SessionState.Started) {
                    "Rokid Glasses에 Codex 상태를 표시하고 있습니다"
                } else {
                    "Hi Rokid를 통해 안경에 연결하는 중"
                },
            )
            return
        }

        // isGlassesBtConnected() can lag behind Hi Rokid's real connection
        // state during activity transitions. Let the session handshake be the
        // source of truth; it reports BT_NOT_CONNECTED when no data link exists.
        val created = sessionManager.create(
            SessionConfig(
                sessionType = SessionType.CUSTOM_VIEW,
                viewData = renderFull(latestSnapshot),
            ),
        )
        session = created
        created.addLifecycleCallback(lifecycleCallback(context, created))
        emitState("connecting", "Hi Rokid를 통해 안경에 연결하는 중")
        created.connect(token)
    }

    private fun lifecycleCallback(context: Context, target: CxrSession) = object : ISessionLifecycleCbk {
        override fun onSessionStarted() {
            if (session !== target) return
            emitState("connected", "Rokid Glasses에 Codex 상태를 표시하고 있습니다")
        }

        override fun onSessionPaused(reason: PausedReason) {
            if (session !== target) return
            emitState(
                "paused",
                if (reason == PausedReason.AI_ASSIST) {
                    "Rokid AI 사용 중에는 Codex 표시가 잠시 멈춥니다"
                } else {
                    "안경 연결이 잠시 멈췄습니다"
                },
            )
        }

        override fun onSessionResumed() {
            if (session !== target) return
            emitState("connected", "Rokid Glasses에 Codex 상태를 표시하고 있습니다")
            target.customViewUpdate(renderUpdate(latestSnapshot))
        }

        override fun onSessionTerminating(reason: TerminatingReason, gracePeriodMs: Long) {
            if (session !== target) return
            emitState("disconnected", "안경 표시 세션을 종료하는 중")
        }

        override fun onSessionClosed(reason: CloseReason) {
            if (session !== target) return
            session = null
            emitState(
                "disconnected",
                if (reason == CloseReason.USER_CLOSED) {
                    "Rokid Glasses 표시를 종료했습니다"
                } else {
                    "Rokid Glasses 연결이 종료되었습니다: ${reason.name}"
                },
            )
        }

        override fun onConnectResult(success: Boolean, errorCode: SessionErrorCode?) {
            if (session !== target || success) return
            session = null
            val code = errorCode ?: SessionErrorCode.UNKNOWN
            when (code) {
                SessionErrorCode.NOT_AUTHENTICATED,
                SessionErrorCode.TOKEN_EXPIRED -> {
                    preferences(context).edit()
                        .putBoolean(PREF_AUTHORIZED, false)
                        .remove(PREF_TOKEN)
                        .apply()
                    emitState("authorization_required", "Hi Rokid 연결을 다시 승인하세요")
                }
                SessionErrorCode.BT_NOT_CONNECTED -> {
                    emitState("disconnected", "Hi Rokid에서 Rokid Glasses를 먼저 연결하세요")
                }
                SessionErrorCode.ROKID_APP_NOT_INSTALLED,
                SessionErrorCode.ROKID_APP_VERSION_LOW -> {
                    emitState("unavailable", "호환되는 Hi Rokid 앱이 필요합니다")
                }
                else -> emitState("error", "Rokid Glasses 연결 실패: ${code.name}")
            }
        }
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
    }
}
