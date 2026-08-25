import * as React from 'react';
import { Platform } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { Item } from '@/components/Item';
import { ItemGroup } from '@/components/ItemGroup';
import { ItemList } from '@/components/ItemList';
import { Switch } from '@/components/Switch';
import { Modal } from '@/modal';
import { useLocalSettingMutable } from '@/sync/storage';
import { nativeRokidBridge } from '@/rokid/nativeBridge';

export default function RokidSettingsScreen() {
    const [enabled, setEnabled] = useLocalSettingMutable('rokidBridgeEnabled');
    const [savedAddress, setSavedAddress] = useLocalSettingMutable('rokidDeviceAddress');
    const state = React.useSyncExternalStore(
        nativeRokidBridge.subscribe,
        nativeRokidBridge.getSnapshot,
        nativeRokidBridge.getSnapshot,
    );

    const changeEnabled = React.useCallback(async (value: boolean) => {
        if (!value) {
            nativeRokidBridge.disconnect();
            setEnabled(false);
            return;
        }
        if (Platform.OS !== 'android' || !state.available) {
            Modal.alert('Rokid Glasses', 'This bridge is available in the Android build only.');
            return;
        }
        const granted = await nativeRokidBridge.requestPermissions();
        if (!granted) {
            Modal.alert('Bluetooth permission required', 'Allow Nearby devices access to pair with Rokid Glass3.');
            return;
        }
        setEnabled(true);
        nativeRokidBridge.initialize(savedAddress);
    }, [savedAddress, setEnabled, state.available]);

    const scan = React.useCallback(async () => {
        const granted = await nativeRokidBridge.requestPermissions();
        if (!granted) return;
        nativeRokidBridge.initialize();
        nativeRokidBridge.startScan();
    }, []);

    const connect = React.useCallback((address: string) => {
        setSavedAddress(address);
        nativeRokidBridge.connect(address);
    }, [setSavedAddress]);

    return (
        <ItemList style={{ paddingTop: 0 }}>
            <ItemGroup
                title="Rokid bridge"
                footer="Only current Codex status and one-time approve/deny controls are forwarded. Always approve and permission-mode changes are blocked."
            >
                <Item
                    title="Enable Rokid Glass3"
                    subtitle={state.available ? state.message ?? state.state : 'Android native module unavailable'}
                    icon={<Ionicons name="glasses-outline" size={29} color="#007AFF" />}
                    rightElement={<Switch value={enabled} onValueChange={changeEnabled} />}
                    showChevron={false}
                />
                {enabled && (
                    <Item
                        title="Scan for Glass3"
                        subtitle="Uses Classic Bluetooth for small status and control messages"
                        icon={<Ionicons name="bluetooth-outline" size={29} color="#5856D6" />}
                        onPress={scan}
                        showChevron={false}
                    />
                )}
                {enabled && state.state === 'connected' && (
                    <Item
                        title="Disconnect"
                        subtitle={state.address ?? savedAddress ?? undefined}
                        icon={<Ionicons name="unlink-outline" size={29} color="#FF3B30" />}
                        onPress={() => nativeRokidBridge.disconnect()}
                        showChevron={false}
                    />
                )}
            </ItemGroup>

            {enabled && state.devices.length > 0 && (
                <ItemGroup title="Found devices">
                    {state.devices.map((device) => (
                        <Item
                            key={device.address}
                            title={device.name || 'Rokid Glass3'}
                            subtitle={device.address}
                            icon={<Ionicons name="glasses" size={29} color="#34C759" />}
                            onPress={() => connect(device.address)}
                        />
                    ))}
                </ItemGroup>
            )}
        </ItemList>
    );
}
