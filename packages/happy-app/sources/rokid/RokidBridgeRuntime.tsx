import * as Crypto from 'expo-crypto';
import * as React from 'react';
import { Platform } from 'react-native';
import { nativeRokidBridge } from './nativeBridge';
import {
    ApprovalNonceRegistry,
    buildHappySessionSnapshot,
    latestAgentResponse,
    stableSnapshotFingerprint,
} from './protocol';
import { storage, useLocalSetting } from '@/sync/storage';

export function RokidBridgeRuntime(): null {
    const enabled = useLocalSetting('rokidBridgeEnabled');

    React.useEffect(() => {
        if (Platform.OS !== 'android' || !enabled) return;

        const registry = new ApprovalNonceRegistry(() => Crypto.randomUUID());
        let lastFingerprint: string | null = null;

        const publishSnapshot = () => {
            const state = storage.getState();
            const configuredPin = state.localSettings.rokidPinnedSessionId;
            const pinnedSession = configuredPin ? state.sessions[configuredPin] : undefined;
            const pinnedSessionId = pinnedSession?.metadata?.lifecycleState === 'archived'
                ? null
                : pinnedSession?.id ?? null;
            if (configuredPin && !pinnedSessionId) {
                state.applyLocalSettings({ rokidPinnedSessionId: null });
            }
            const now = Date.now();
            const latestResponses = Object.fromEntries(
                Object.entries(state.sessionMessages).flatMap(([sessionId, sessionMessages]) => {
                    const response = latestAgentResponse(sessionMessages.messages);
                    return response ? [[sessionId, response]] : [];
                }),
            );
            const snapshot = buildHappySessionSnapshot(
                state.sessions,
                state.currentViewingSessionId,
                pinnedSessionId,
                registry,
                now,
                latestResponses,
            );
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
        nativeRokidBridge.initialize();

        return () => {
            unsubscribeNative();
            unsubscribeStore();
            clearInterval(refreshTimer);
        };
    }, [enabled]);

    return null;
}
