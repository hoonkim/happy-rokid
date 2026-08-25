import HappyRokidModule, {
    type RokidNativeState,
    type RokidStateEvent,
} from '../../modules/happy-rokid';

export interface RokidBridgeViewState {
    available: boolean;
    state: RokidNativeState;
    message?: string;
}

class NativeRokidBridge {
    private snapshot: RokidBridgeViewState = {
        available: HappyRokidModule !== null,
        state: HappyRokidModule ? 'idle' : 'unavailable',
    };
    private readonly viewListeners = new Set<() => void>();
    private subscriptionsStarted = false;

    getSnapshot = (): RokidBridgeViewState => this.snapshot;

    subscribe = (listener: () => void): (() => void) => {
        this.viewListeners.add(listener);
        return () => this.viewListeners.delete(listener);
    };

    initialize(): boolean {
        if (!HappyRokidModule) return false;
        this.startSubscriptions();
        return HappyRokidModule.initialize();
    }

    authorize(): boolean {
        if (!HappyRokidModule) return false;
        this.startSubscriptions();
        return HappyRokidModule.authorize();
    }

    disconnect(): boolean {
        return HappyRokidModule?.disconnect() ?? false;
    }

    send(message: string): boolean {
        if (!HappyRokidModule || this.snapshot.state !== 'connected') return false;
        return HappyRokidModule.send(message);
    }

    private startSubscriptions(): void {
        if (!HappyRokidModule || this.subscriptionsStarted) return;
        this.subscriptionsStarted = true;
        HappyRokidModule.addListener('onRokidState', (event: RokidStateEvent) => {
            this.update({ ...this.snapshot, ...event, available: true });
        });
    }

    private update(snapshot: RokidBridgeViewState): void {
        this.snapshot = snapshot;
        this.viewListeners.forEach((listener) => listener());
    }
}

export const nativeRokidBridge = new NativeRokidBridge();
