import * as React from 'react';
import { Platform } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { Item } from '@/components/Item';
import { ItemGroup } from '@/components/ItemGroup';
import { ItemList } from '@/components/ItemList';
import { Switch } from '@/components/Switch';
import { Modal } from '@/modal';
import { useAllSessions, useLocalSettingMutable } from '@/sync/storage';
import { nativeRokidBridge } from '@/rokid/nativeBridge';
import { getSessionName } from '@/utils/sessionUtils';

export default function RokidSettingsScreen() {
    const [enabled, setEnabled] = useLocalSettingMutable('rokidBridgeEnabled');
    const [pinnedSessionId, setPinnedSessionId] = useLocalSettingMutable('rokidPinnedSessionId');
    const sessions = useAllSessions();
    const pinnedSession = sessions.find((session) => (
        session.id === pinnedSessionId && session.metadata?.lifecycleState !== 'archived'
    )) ?? null;
    const activeSessionCount = sessions.filter((session) => (
        session.metadata?.lifecycleState !== 'archived'
        && session.active
        && session.presence === 'online'
    )).length;
    const state = React.useSyncExternalStore(
        nativeRokidBridge.subscribe,
        nativeRokidBridge.getSnapshot,
        nativeRokidBridge.getSnapshot,
    );

    const changeEnabled = React.useCallback((value: boolean) => {
        if (!value) {
            nativeRokidBridge.disconnect();
            setEnabled(false);
            return;
        }
        if (Platform.OS !== 'android' || !state.available) {
            Modal.alert('Rokid Glasses', 'This bridge is available in the Android build only.');
            return;
        }
        setEnabled(true);
        nativeRokidBridge.initialize();
    }, [setEnabled, state.available]);

    const authorize = React.useCallback(() => {
        if (!nativeRokidBridge.authorize()) {
            Modal.alert('Hi Rokid unavailable', 'Install or update Hi Rokid, then pair Rokid Glasses in that app.');
        }
    }, []);

    return (
        <ItemList style={{ paddingTop: 0 }}>
            <ItemGroup
                title="Rokid Glasses"
                footer="Shows up to three active Happy sessions. Approval requests from every session remain visible; approve or deny them in Happy on the phone for now."
            >
                <Item
                    title="Show Happy sessions"
                    subtitle={state.available ? state.message ?? state.state : 'Android native module unavailable'}
                    icon={<Ionicons name="glasses-outline" size={29} color="#007AFF" />}
                    rightElement={<Switch value={enabled} onValueChange={changeEnabled} />}
                    showChevron={false}
                />

                <Item
                    title={pinnedSession ? 'Pinned Rokid session' : 'Rokid session selection'}
                    subtitle={pinnedSession
                        ? `${getSessionName(pinnedSession)} · Tap to return to automatic selection`
                        : 'Automatic: open session, approvals, working, then recent activity'}
                    detail={pinnedSession ? 'Pinned' : `${activeSessionCount} active`}
                    icon={<Ionicons name={pinnedSession ? 'pin' : 'git-compare-outline'} size={29} color="#5856D6" />}
                    onPress={pinnedSession ? () => setPinnedSessionId(null) : undefined}
                    showChevron={false}
                />

                {enabled && state.state === 'authorization_required' && (
                    <Item
                        title="Authorize with Hi Rokid"
                        subtitle="Hi Rokid will ask permission to display Happy content"
                        icon={<Ionicons name="shield-checkmark-outline" size={29} color="#34C759" />}
                        onPress={authorize}
                        showChevron={false}
                    />
                )}

                {enabled && ['disconnected', 'error', 'ready'].includes(state.state) && (
                    <Item
                        title="Connect through Hi Rokid"
                        subtitle="Make sure Rokid Glasses is already connected in Hi Rokid"
                        icon={<Ionicons name="link-outline" size={29} color="#5856D6" />}
                        onPress={() => nativeRokidBridge.initialize()}
                        showChevron={false}
                    />
                )}

                {enabled && ['connected', 'paused', 'connecting'].includes(state.state) && (
                    <Item
                        title="Stop glasses display"
                        subtitle="Closes the CXR-L custom view"
                        icon={<Ionicons name="unlink-outline" size={29} color="#FF3B30" />}
                        onPress={() => changeEnabled(false)}
                        showChevron={false}
                    />
                )}
            </ItemGroup>

            <ItemGroup title="Requirements">
                <Item
                    title="Hi Rokid"
                    subtitle="Install the official app and pair Rokid Glasses there first"
                    icon={<Ionicons name="phone-portrait-outline" size={29} color="#007AFF" />}
                    showChevron={false}
                />
                <Item
                    title="No glasses APK"
                    subtitle="Happy session status is rendered by Hi Rokid using CXR-L CUSTOM_VIEW"
                    icon={<Ionicons name="checkmark-circle-outline" size={29} color="#34C759" />}
                    showChevron={false}
                />
            </ItemGroup>
        </ItemList>
    );
}
