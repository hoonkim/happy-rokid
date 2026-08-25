import * as Crypto from 'expo-crypto';
import * as React from 'react';
import { Platform } from 'react-native';
import { nativeRokidBridge } from './nativeBridge';
import {
    ApprovalNonceRegistry,
    buildOfflineSessionSnapshot,
    buildSessionSnapshot,
    parseDecisionMessage,
    ROKID_PROTOCOL_VERSION,
    selectPrimaryCodexSession,
    stableSnapshotFingerprint,
    type RokidDecisionAck,
} from './protocol';
import { storage, useLocalSetting } from '@/sync/storage';
import { sessionAllow, sessionDeny } from '@/sync/ops';

export function RokidBridgeRuntime(): null {
    const enabled = useLocalSetting('rokidBridgeEnabled');
    const deviceAddress = useLocalSetting('rokidDeviceAddress');

    React.useEffect(() => {
        if (Platform.OS !== 'android' || !enabled) return;

        const registry = new ApprovalNonceRegistry(() => Crypto.randomUUID());
        let lastFingerprint: string | null = null;

        const publishSnapshot = () => {
            const state = storage.getState();
            const session = selectPrimaryCodexSession(state.sessions, state.currentViewingSessionId);
            const now = Date.now();
            if (!session) registry.prune(new Set(), now);
            const snapshot = session
                ? buildSessionSnapshot(session, registry, now)
                : buildOfflineSessionSnapshot(now);
            const fingerprint = stableSnapshotFingerprint(snapshot);
            if (fingerprint === lastFingerprint) return;
            if (nativeRokidBridge.send(JSON.stringify(snapshot))) {
                lastFingerprint = fingerprint;
            }
        };

        const unsubscribeStore = storage.subscribe(publishSnapshot);
        const refreshTimer = setInterval(publishSnapshot, 30_000);
        const unsubscribeNative = nativeRokidBridge.subscribe(() => {
            if (nativeRokidBridge.getSnapshot().state === 'connected') {
                lastFingerprint = null;
                publishSnapshot();
            }
        });
        const unsubscribeMessages = nativeRokidBridge.subscribeMessages(({ message }) => {
            void handleDecision(message, registry).then((ack) => {
                nativeRokidBridge.send(JSON.stringify(ack));
                lastFingerprint = null;
                publishSnapshot();
            });
        });

        void hasBluetoothPermission().then((granted) => {
            if (granted) nativeRokidBridge.initialize(deviceAddress);
        });

        return () => {
            unsubscribeMessages();
            unsubscribeNative();
            unsubscribeStore();
            clearInterval(refreshTimer);
        };
    }, [deviceAddress, enabled]);

    return null;
}

async function handleDecision(
    raw: string,
    registry: ApprovalNonceRegistry,
): Promise<RokidDecisionAck> {
    const now = Date.now();
    const parsed = parseDecisionMessage(raw);
    if (!parsed) return rejectedAck('', '', 'deny', 'Invalid decision message', now);

    const session = storage.getState().sessions[parsed.sessionId];
    const rejection = registry.validate(parsed, session, now);
    if (rejection) {
        return rejectedAck(parsed.sessionId, parsed.requestId, parsed.decision, rejection, now);
    }

    // Reserve the one-time response before crossing the async Happy boundary so
    // duplicated Bluetooth messages cannot race each other.
    registry.consume(parsed.sessionId, parsed.requestId);
    try {
        if (parsed.decision === 'approve') {
            await sessionAllow(parsed.sessionId, parsed.requestId, undefined, undefined, 'approved');
        } else {
            await sessionDeny(parsed.sessionId, parsed.requestId, undefined, undefined, 'denied');
        }
        return {
            v: ROKID_PROTOCOL_VERSION,
            type: 'decision_ack',
            sessionId: parsed.sessionId,
            requestId: parsed.requestId,
            decision: parsed.decision,
            accepted: true,
            sentAt: now,
        };
    } catch {
        return rejectedAck(parsed.sessionId, parsed.requestId, parsed.decision, 'Happy rejected the decision', now);
    }
}

function rejectedAck(
    sessionId: string,
    requestId: string,
    decision: 'approve' | 'deny',
    reason: string,
    now: number,
): RokidDecisionAck {
    return {
        v: ROKID_PROTOCOL_VERSION,
        type: 'decision_ack',
        sessionId,
        requestId,
        decision,
        accepted: false,
        reason,
        sentAt: now,
    };
}

async function hasBluetoothPermission(): Promise<boolean> {
    if (Platform.OS !== 'android') return false;
    const { PermissionsAndroid } = await import('react-native');
    if (Platform.Version >= 31) {
        return (await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN)) &&
            (await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT));
    }
    return PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION);
}
