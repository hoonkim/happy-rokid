import { describe, expect, it } from 'vitest';
import type { Session } from '@/sync/storageTypes';
import type { Message } from '@/sync/typesMessage';
import {
    ApprovalNonceRegistry,
    buildHappySessionSnapshot,
    buildOfflineSessionSnapshot,
    buildSessionSnapshot,
    latestAgentResponse,
    parseDecisionMessage,
    ROKID_PROTOCOL_VERSION,
    selectPrimaryHappySession,
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
    it('prioritizes a Happy session waiting for permission across agent flavors', () => {
        const working = makeSession({ id: 'working', thinking: true });
        const permission = makeSession({
            id: 'permission',
            metadata: { path: '/work/docs', host: 'mac', flavor: 'claude' },
            agentState: { requests: { req: { tool: 'CodexBash', arguments: {}, createdAt: 10 } } },
        });
        expect(selectPrimaryHappySession({ working, permission }, null, null)?.id).toBe('permission');
    });

    it('keeps a pinned session primary while preserving other sessions in the dashboard', () => {
        const pinned = makeSession({
            id: 'pinned',
            metadata: { path: '/work/pinned', host: 'mac', flavor: 'claude' },
            thinking: false,
        });
        const permission = makeSession({
            id: 'permission',
            metadata: { path: '/work/api', host: 'mac', flavor: 'codex' },
            agentState: { requests: { req: { tool: 'CodexBash', arguments: {}, createdAt: 10 } } },
        });
        const snapshot = buildHappySessionSnapshot(
            { pinned, permission },
            null,
            pinned.id,
            new ApprovalNonceRegistry(() => 'nonce'),
            20,
        );

        expect(snapshot.session).toMatchObject({ id: 'pinned', agent: 'Claude', pinned: true });
        expect(snapshot.sessions.map((session) => session.id)).toEqual(['pinned', 'permission']);
        expect(snapshot.approvalCount).toBe(1);
        expect(snapshot.approvals[0]).toMatchObject({ sessionId: 'permission', agent: 'Codex' });
    });

    it('uses the session currently open in Happy when nothing is pinned', () => {
        const viewed = makeSession({ id: 'viewed', activeAt: 1 });
        const permission = makeSession({
            id: 'permission',
            activeAt: 100,
            agentState: { requests: { req: { tool: 'CodexBash', arguments: {}, createdAt: 10 } } },
        });

        expect(selectPrimaryHappySession({ viewed, permission }, viewed.id, null)?.id).toBe('viewed');
    });

    it('shows up to eight session rows while reporting every active session', () => {
        const sessions = Object.fromEntries(Array.from({ length: 10 }, (_, index) => {
            const session = makeSession({
                id: `session-${index}`,
                updatedAt: index,
                activeAt: index,
            });
            return [session.id, session];
        }));
        const snapshot = buildHappySessionSnapshot(
            sessions,
            null,
            null,
            new ApprovalNonceRegistry(() => 'nonce'),
            20,
        );

        expect(snapshot.sessions).toHaveLength(8);
        expect(snapshot.sessionCount).toBe(10);
        expect(snapshot.activeSessionCount).toBe(10);
    });

    it('includes the primary session latest response while preserving line breaks', () => {
        const snapshot = buildHappySessionSnapshot(
            { primary: makeSession({ id: 'primary' }) },
            'primary',
            null,
            new ApprovalNonceRegistry(() => 'nonce'),
            20,
            { primary: '첫 줄\n둘째 줄' },
        );

        expect(snapshot.session.latestResponse).toBe('첫 줄\n둘째 줄');
        expect(snapshot.sessions[0].latestResponse).toBeUndefined();
    });

    it('selects the newest visible agent answer and redacts secrets', () => {
        const messages: Message[] = [
            {
                kind: 'agent-text',
                id: 'thinking',
                localId: null,
                createdAt: 3,
                text: '분석 중',
                isThinking: true,
            },
            {
                kind: 'agent-text',
                id: 'answer',
                localId: null,
                createdAt: 2,
                text: '완료했습니다.\ntoken=super-secret-value',
            },
            {
                kind: 'agent-text',
                id: 'old-answer',
                localId: null,
                createdAt: 1,
                text: '예전 답변',
            },
        ];

        expect(latestAgentResponse(messages)).toBe('완료했습니다.\ntoken=[가림]');
    });

    it('does not keep an archived session pinned', () => {
        const archived = makeSession({
            id: 'archived',
            metadata: { path: '/work/old', host: 'mac', flavor: 'claude', lifecycleState: 'archived' },
        });
        const active = makeSession({ id: 'active' });

        expect(selectPrimaryHappySession({ archived, active }, null, archived.id)?.id).toBe('active');
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
            v: ROKID_PROTOCOL_VERSION,
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
            v: ROKID_PROTOCOL_VERSION,
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
            v: ROKID_PROTOCOL_VERSION,
            type: 'decision',
            sessionId: session.id,
            requestId: 'req',
            nonce: 'nonce-1',
            decision: 'deny',
            sentAt: 249,
        }))!;
        expect(registry.validate(message, session, 250)).toBe('Approval request expired');
    });

    it('emits an offline snapshot when no Happy session exists', () => {
        const snapshot = buildOfflineSessionSnapshot(300);
        expect(snapshot.session).toMatchObject({ id: 'none', title: 'Happy 세션 없음', status: 'offline' });
        expect(snapshot.sessionCount).toBe(0);
        expect(snapshot.activeSessionCount).toBe(0);
        expect(snapshot.approvals).toEqual([]);
    });
});
