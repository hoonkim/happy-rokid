package com.hoonkim.happy.rokid

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.core.os.bundleOf
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.bluetooth.classic.listener.IClassicBTClientListener
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.base.data.EngineParam
import com.rokid.security.phone.sdk.base.data.EnvType
import com.rokid.security.phone.sdk.base.data.NetServiceType
import com.rokid.security.phone.sdk.base.data.UserAuthInfo
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class HappyRokidModule : Module() {
    private var listenersAttached = false
    private var connectedAddress: String? = null

    private val bluetoothListener = object : IClassicBTClientListener {
        override fun onDeviceFound(device: BluetoothDevice) {
            val name = device.name ?: return
            if (!name.contains("Glass3", ignoreCase = true)) return
            sendEvent("onRokidDevice", bundleOf("name" to name, "address" to device.address))
        }

        override fun onScanFinished() {
            emitState("ready", "Scan finished")
        }

        override fun onConnect(success: Boolean) {
            emitState(
                if (success) "connected" else "disconnected",
                if (success) "Rokid Glass3 connected" else "Rokid Glass3 connection failed",
                connectedAddress,
            )
        }

        override fun onConnectionRejected(reason: String, code: Int) {
            emitState("error", "Connection rejected ($code): $reason", connectedAddress)
        }
    }

    private val messageListener = object : IMessageListener {
        override fun onClassicBTTextMessage(msg: String, clientId: String) {
            if (msg.length > MAX_MESSAGE_LENGTH) return
            if (clientId.isNotBlank() &&
                clientId != GLASSES_CLIENT_ID &&
                clientId != PHONE_CLIENT_ID
            ) return
            sendEvent(
                "onRokidMessage",
                bundleOf("message" to msg, "clientId" to clientId, "transport" to "bluetooth"),
            )
        }
    }

    override fun definition() = ModuleDefinition {
        Name("HappyRokid")
        Events("onRokidState", "onRokidDevice", "onRokidMessage")

        Function("initialize") {
            val engine = PSecuritySDK.getMobileEngineService()
            if (engine.isInit()) {
                attachListeners()
                emitState("ready", "Rokid SDK ready")
                return@Function true
            }

            emitState("initializing", "Initializing Rokid SDK")
            val params = EngineParam(
                clientIds = arrayListOf(PHONE_CLIENT_ID, GLASSES_CLIENT_ID),
                userAuthInfo = UserAuthInfo("", ""),
                banServiceList = arrayListOf(NetServiceType.ALL),
                envType = EnvType.PUBLIC,
            )
            engine.initSDK(params) { result ->
                if (result.isSuccess) {
                    attachListeners()
                    emitState("ready", "Rokid SDK ready")
                } else {
                    emitState("error", "Rokid SDK initialization failed")
                }
            }
            true
        }

        Function("startScan") { timeoutMs: Double ->
            val client = PSecuritySDK.getClassicBlueToothClientService() ?: return@Function false
            attachListeners()
            emitState("scanning", "Scanning for Glass3")
            client.startScan(timeoutMs.toLong().coerceIn(1_000L, 30_000L))
            true
        }

        Function("connect") { address: String ->
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@Function false
            val client = PSecuritySDK.getClassicBlueToothClientService() ?: return@Function false
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                return@Function false
            }
            connectedAddress = address
            emitState("connecting", "Connecting to Rokid Glass3", address)
            client.connectToServer(device) { success ->
                emitState(
                    if (success) "connected" else "disconnected",
                    if (success) "Rokid Glass3 connected" else "Rokid Glass3 connection failed",
                    address,
                )
            }
            true
        }

        Function("disconnect") {
            PSecuritySDK.getClassicBlueToothClientService()?.disconnect()
            emitState("disconnected", "Rokid Glass3 disconnected", connectedAddress)
            connectedAddress = null
            true
        }

        Function("send") { message: String ->
            if (message.length > MAX_MESSAGE_LENGTH) return@Function false
            val client = PSecuritySDK.getClassicBlueToothClientService() ?: return@Function false
            if (!client.isConnected()) return@Function false
            val service = PSecuritySDK.getMessageService() ?: return@Function false
            service.sendTextMessageByClassicBT(message, GLASSES_CLIENT_ID)
            true
        }
    }

    private fun attachListeners() {
        if (listenersAttached) return
        PSecuritySDK.getClassicBlueToothClientService()?.addClientListener(bluetoothListener)
        PSecuritySDK.getMessageService()?.addMessageListener(messageListener)
        listenersAttached = true
    }

    private fun emitState(state: String, message: String, address: String? = null) {
        sendEvent(
            "onRokidState",
            bundleOf("state" to state, "message" to message, "address" to address),
        )
    }

    companion object {
        private const val PHONE_CLIENT_ID = "HappyRokidPhone"
        private const val GLASSES_CLIENT_ID = "HappyRokidGlasses"
        private const val MAX_MESSAGE_LENGTH = 4096
    }
}
