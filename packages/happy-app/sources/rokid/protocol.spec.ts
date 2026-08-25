import { describe, expect, it } from 'vitest';
import type { Session } from '@/sync/storageTypes';
import {
    ApprovalNonceRegistry,
    buildOfflineSessionSnapshot,
    buildSessionSnapshot,
    parseDecisionMessage,
    selectPrimaryCodexSession,
} from './protocol';

function makeSession(overrides: Partial<Session> = {}): Session {
    return {
        id: 'session-1',
        seq: 1,
        createdAt: 1,
        updatedAt: 2,
        active: true,
        activeAt: 3,
        metadata: { path: '/work/rokid', host: 'mac', flavor: 'codex' },
        metadataVersion: 1,
        agentState: null,
        agentStateVersion: 1,
        thinking: false,
        thinkingAt: 2,
        presence: 'online',
        ...overrides,
    };
}

describe('Rokid protocol', () => {
    it('prioritizes a Codex session waiting for permission', () => {
        const working = makeSession({ id: 'working', thinking: true });
        const permission = makeSession({
            id: 'permission',
            agentState: { requests: { req: { tool: 'CodexBash', arguments: {}, createdAt: 10 } } },
        });
        expect(selectPrimaryCodexSession({ working, permission }, null)?.id).toBe('permission');
    });

    it('redacts secrets and emits one-time actions only', () => {
        const registry = new ApprovalNonceRegistry(() => 'nonce-1');
        const session = makeSession({
            agentState: {
                requests: {
                    req: {
                        tool: 'CodexBash',
                        arguments: { command: 'TOKEN=secret-value run', apiKey: 'hidden' },
                        createdAt: 100,
                    },
                },
            },
        });
        const snapshot = buildSessionSnapshot(session, registry, 200);
        expect(snapshot.session.status).toBe('permission_required');
        expect(snapshot.approvals[0].actions).toEqual(['approve', 'deny']);
        expect(snapshot.approvals[0].summary).not.toContain('hidden');
        expect(snapshot.approvals[0].summary).not.toContain('secret-value');
        expect(snapshot.approvals[0].nonce).toBe('nonce-1');
    });

    it('rejects replay after a decision is consumed', () => {
        const registry = new ApprovalNonceRegistry(() => 'nonce-1');
        const session = makeSession({
            agentState: { requests: { req: { tool: 'CodexBash', arguments: {}, createdAt: 100 } } },
        });
        buildSessionSnapshot(session, registry, 200);
        const message = parseDecisionMessage(JSON.stringify({
            v: 1,
            type: 'decision',
            sessionId: session.id,
            requestId: 'req',
            nonce: 'nonce-1',
            decision: 'approve',
            sentAt: 201,
        }))!;
        expect(registry.validate(message, session, 202)).toBeNull();
        registry.consume(session.id, 'req');
        expect(registry.validate(message, session, 203)).toBe('Approval token was already used');
        expect(buildSessionSnapshot(session, registry, 204).approvals).toEqual([]);
    });

    it('does not accept persistent approval decisions in the wire format', () => {
        expect(parseDecisionMessage(JSON.stringify({
            v: 1,
            type: 'decision',
            sessionId: 'session-1',
            requestId: 'req',
            nonce: 'nonce',
            decision: 'approved_for_session',
            sentAt: 200,
        }))).toBeNull();
    });

    it('limits snapshots to five oldest approvals for the Bluetooth message budget', () => {
        const registry = new ApprovalNonceRegistry(() => 'nonce');
        const requests = Object.fromEntries(Array.from({ length: 8 }, (_, index) => [
            `req-${index}`,
            { tool: 'CodexBash', arguments: { command: `command-${index}` }, createdAt: index + 1 },
        ]));
        const snapshot = buildSessionSnapshot(makeSession({ agentState: { requests } }), registry, 100);
        expect(snapshot.approvals.map((approval) => approval.requestId)).toEqual([
            'req-0', 'req-1', 'req-2', 'req-3', 'req-4',
        ]);
        expect(JSON.stringify(snapshot).length).toBeLessThan(4096);
    });

    it('rejects an expired approval token', () => {
        const registry = new ApprovalNonceRegistry(() => 'nonce-1', 50);
        const session = makeSession({
            agentState: { requests: { req: { tool: 'CodexBash', arguments: {}, createdAt: 100 } } },
        });
        buildSessionSnapshot(session, registry, 200);
        const message = parseDecisionMessage(JSON.stringify({
            v: 1,
            type: 'decision',
            sessionId: session.id,
            requestId: 'req',
            nonce: 'nonce-1',
            decision: 'deny',
            sentAt: 249,
        }))!;
        expect(registry.validate(message, session, 250)).toBe('Approval request expired');
    });

    it('emits an offline snapshot when no Codex session exists', () => {
        const snapshot = buildOfflineSessionSnapshot(300);
        expect(snapshot.session).toMatchObject({ id: 'none', status: 'offline' });
        expect(snapshot.approvals).toEqual([]);
    });
});
