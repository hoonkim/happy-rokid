import { requireOptionalNativeModule, type NativeModule } from 'expo-modules-core';

export type RokidNativeState =
    | 'unavailable'
    | 'idle'
    | 'initializing'
    | 'ready'
    | 'scanning'
    | 'connecting'
    | 'connected'
    | 'disconnected'
    | 'error';

export interface RokidStateEvent {
    state: RokidNativeState;
    message?: string;
    address?: string;
}

export interface RokidDeviceEvent {
    name: string;
    address: string;
}

export interface RokidMessageEvent {
    message: string;
    clientId: string;
    transport: 'bluetooth';
}

interface HappyRokidNativeModule extends NativeModule {
    initialize(): boolean;
    startScan(timeoutMs: number): boolean;
    connect(address: string): boolean;
    disconnect(): boolean;
    send(message: string): boolean;
    addListener(eventName: 'onRokidState', listener: (event: RokidStateEvent) => void): { remove(): void };
    addListener(eventName: 'onRokidDevice', listener: (event: RokidDeviceEvent) => void): { remove(): void };
    addListener(eventName: 'onRokidMessage', listener: (event: RokidMessageEvent) => void): { remove(): void };
}

export default requireOptionalNativeModule<HappyRokidNativeModule>('HappyRokid');
