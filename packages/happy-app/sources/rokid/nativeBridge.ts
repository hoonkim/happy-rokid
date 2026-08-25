import { PermissionsAndroid, Platform } from 'react-native';
import HappyRokidModule, {
    type RokidDeviceEvent,
    type RokidMessageEvent,
    type RokidNativeState,
    type RokidStateEvent,
} from '../../modules/happy-rokid';

export interface RokidBridgeViewState {
    available: boolean;
    state: RokidNativeState;
    message?: string;
    address?: string;
    devices: RokidDeviceEvent[];
}

class NativeRokidBridge {
    private snapshot: RokidBridgeViewState = {
        available: HappyRokidModule !== null,
        state: HappyRokidModule ? 'idle' : 'unavailable',
        devices: [],
    };
    private readonly viewListeners = new Set<() => void>();
    private readonly messageListeners = new Set<(event: RokidMessageEvent) => void>();
    private autoConnectAddress: string | null = null;
    private subscriptionsStarted = false;

    getSnapshot = (): RokidBridgeViewState => this.snapshot;

    subscribe = (listener: () => void): (() => void) => {
        this.viewListeners.add(listener);
        return () => this.viewListeners.delete(listener);
    };

    subscribeMessages(listener: (event: RokidMessageEvent) => void): () => void {
        this.messageListeners.add(listener);
        return () => this.messageListeners.delete(listener);
    }

    async requestPermissions(): Promise<boolean> {
        if (Platform.OS !== 'android') return false;
        const permissions = Platform.Version >= 31
            ? [PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN, PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT]
            : [PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION];
        const results = await PermissionsAndroid.requestMultiple(permissions);
        return permissions.every((permission) => results[permission] === PermissionsAndroid.RESULTS.GRANTED);
    }

    initialize(autoConnectAddress?: string | null): boolean {
        if (!HappyRokidModule) return false;
        this.startSubscriptions();
        this.autoConnectAddress = autoConnectAddress ?? null;
        return HappyRokidModule.initialize();
    }

    startScan(timeoutMs = 12_000): boolean {
        if (!HappyRokidModule) return false;
        this.update({ ...this.snapshot, devices: [] });
        return HappyRokidModule.startScan(timeoutMs);
    }

    connect(address: string): boolean {
        if (!HappyRokidModule) return false;
        this.autoConnectAddress = null;
        return HappyRokidModule.connect(address);
    }

    disconnect(): boolean {
        this.autoConnectAddress = null;
        return HappyRokidModule?.disconnect() ?? false;
    }

    send(message: string): boolean {
        if (!HappyRokidModule || this.snapshot.state !== 'connected') return false;
        return HappyRokidModule.send(message);
    }

    private startSubscriptions(): void {
        if (!HappyRokidModule || this.subscriptionsStarted) return;
        const module = HappyRokidModule;
        this.subscriptionsStarted = true;
        module.addListener('onRokidState', (event: RokidStateEvent) => {
            this.update({ ...this.snapshot, ...event, available: true });
            if (event.state === 'ready' && this.autoConnectAddress) {
                const address = this.autoConnectAddress;
                this.autoConnectAddress = null;
                module.connect(address);
            }
        });
        module.addListener('onRokidDevice', (event: RokidDeviceEvent) => {
            const devices = this.snapshot.devices.filter((device) => device.address !== event.address);
            this.update({ ...this.snapshot, devices: [...devices, event] });
        });
        module.addListener('onRokidMessage', (event: RokidMessageEvent) => {
            this.messageListeners.forEach((listener) => listener(event));
        });
    }

    private update(snapshot: RokidBridgeViewState): void {
        this.snapshot = snapshot;
        this.viewListeners.forEach((listener) => listener());
    }
}

export const nativeRokidBridge = new NativeRokidBridge();
