import * as Crypto from 'expo-crypto';
import * as React from 'react';
import { Platform } from 'react-native';
import { nativeRokidBridge } from './nativeBridge';
import {
    ApprovalNonceRegistry,
    buildOfflineSessionSnapshot,
    buildSessionSnapshot,
    selectPrimaryCodexSession,
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
        nativeRokidBridge.initialize();

        return () => {
            unsubscribeNative();
            unsubscribeStore();
            clearInterval(refreshTimer);
        };
    }, [enabled]);

    return null;
}
