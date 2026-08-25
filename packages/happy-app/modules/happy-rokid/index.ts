import { requireOptionalNativeModule, type NativeModule } from 'expo-modules-core';

export type RokidNativeState =
    | 'unavailable'
    | 'idle'
    | 'ready'
    | 'authorization_required'
    | 'authorizing'
    | 'connecting'
    | 'connected'
    | 'paused'
    | 'disconnected'
    | 'error';

export interface RokidStateEvent {
    state: RokidNativeState;
    message?: string;
}

interface HappyRokidNativeModule extends NativeModule {
    initialize(): boolean;
    authorize(): boolean;
    disconnect(): boolean;
    send(message: string): boolean;
    addListener(eventName: 'onRokidState', listener: (event: RokidStateEvent) => void): { remove(): void };
}

export default requireOptionalNativeModule<HappyRokidNativeModule>('HappyRokid');
