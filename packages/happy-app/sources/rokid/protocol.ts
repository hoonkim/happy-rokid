import type { Session } from '@/sync/storageTypes';
import type { Message } from '@/sync/typesMessage';

export const ROKID_PROTOCOL_VERSION = 2 as const;
export const ROKID_APPROVAL_TTL_MS = 10 * 60 * 1000;
export const ROKID_MAX_APPROVALS = 5;
export const ROKID_MAX_VISIBLE_SESSIONS = 8;

export type RokidSessionStatus = 'offline' | 'ready' | 'working' | 'permission_required';
export type RokidDecision = 'approve' | 'deny';

export interface RokidApproval {
    sessionId: string;
    sessionTitle: string;
    agent: string;
    requestId: string;
    nonce: string;
    tool: string;
    summary: string;
    createdAt: number;
    expiresAt: number;
    actions: readonly RokidDecision[];
}

export interface RokidSessionSummary {
    id: string;
    title: string;
    agent: string;
    status: RokidSessionStatus;
    updatedAt: number;
    pinned: boolean;
    latestResponse?: string;
}

export interface RokidSessionSnapshot {
    v: typeof ROKID_PROTOCOL_VERSION;
    type: 'session_state';
    sentAt: number;
    session: RokidSessionSummary;
    sessions: RokidSessionSummary[];
    sessionCount: number;
    activeSessionCount: number;
    approvalCount: number;
    approvals: RokidApproval[];
}

export interface RokidDecisionMessage {
    v: typeof ROKID_PROTOCOL_VERSION;
    type: 'decision';
    sessionId: string;
    requestId: string;
    nonce: string;
    decision: RokidDecision;
    sentAt: number;
}

export interface RokidDecisionAck {
    v: typeof ROKID_PROTOCOL_VERSION;
    type: 'decision_ack';
    sessionId: string;
    requestId: string;
    decision: RokidDecision;
    accepted: boolean;
    reason?: string;
    sentAt: number;
}

interface NonceEntry {
    nonce: string;
    createdAt: number;
    expiresAt: number;
    consumed: boolean;
}

export class ApprovalNonceRegistry {
    private readonly entries = new Map<string, NonceEntry>();

    constructor(
        private readonly nonceFactory: () => string,
        private readonly ttlMs: number = ROKID_APPROVAL_TTL_MS,
    ) {}

    getOrCreate(sessionId: string, requestId: string, createdAt: number, now: number): NonceEntry {
        const key = approvalKey(sessionId, requestId);
        const current = this.entries.get(key);
        if (current && current.createdAt === createdAt) {
            if (current.consumed || current.expiresAt > now) return current;
        }
        const entry: NonceEntry = {
            nonce: this.nonceFactory(),
            createdAt,
            expiresAt: now + this.ttlMs,
            consumed: false,
        };
        this.entries.set(key, entry);
        return entry;
    }

    validate(message: RokidDecisionMessage, session: Session | undefined, now: number): string | null {
        if (!session) return 'Happy session not found';
        const request = session.agentState?.requests?.[message.requestId];
        if (!request) return 'Permission request is no longer pending';

        const entry = this.entries.get(approvalKey(message.sessionId, message.requestId));
        if (!entry || entry.nonce !== message.nonce) return 'Approval token does not match';
        if (entry.consumed) return 'Approval token was already used';
        if (entry.expiresAt <= now) return 'Approval request expired';
        if ((request.createdAt ?? entry.createdAt) !== entry.createdAt) return 'Approval request changed';
        if (Math.abs(now - message.sentAt) > this.ttlMs) return 'Decision message expired';
        return null;
    }

    consume(sessionId: string, requestId: string): void {
        const entry = this.entries.get(approvalKey(sessionId, requestId));
        if (entry) entry.consumed = true;
    }

    prune(activeKeys: Set<string>, now: number): void {
        for (const [key, entry] of this.entries) {
            if (!activeKeys.has(key) || (!entry.consumed && entry.expiresAt <= now)) {
                this.entries.delete(key);
            }
        }
    }
}

export function selectPrimaryHappySession(
    sessions: Record<string, Session>,
    currentViewingSessionId: string | null,
    pinnedSessionId: string | null,
): Session | null {
    const pinned = pinnedSessionId ? sessions[pinnedSessionId] : undefined;
    if (pinned && !isArchived(pinned)) return pinned;

    const viewed = currentViewingSessionId ? sessions[currentViewingSessionId] : undefined;
    if (viewed && !isArchived(viewed)) return viewed;

    const candidates = Object.values(sessions).filter(isDashboardRelevant);

    return candidates.sort(compareSessionPriority)[0] ?? null;
}

export function buildHappySessionSnapshot(
    sessions: Record<string, Session>,
    currentViewingSessionId: string | null,
    pinnedSessionId: string | null,
    registry: ApprovalNonceRegistry,
    now: number,
    latestResponses: Readonly<Record<string, string>> = {},
): RokidSessionSnapshot {
    const primary = selectPrimaryHappySession(sessions, currentViewingSessionId, pinnedSessionId);
    if (!primary) {
        registry.prune(new Set(), now);
        return buildOfflineSessionSnapshot(now);
    }

    const dashboardSessions = orderDashboardSessions(Object.values(sessions), primary);
    const activeKeys = new Set<string>();
    const approvalCandidates = dashboardSessions.flatMap((session) => (
        Object.entries(session.agentState?.requests ?? {}).map(([requestId, request]) => ({
            session,
            requestId,
            request,
        }))
    ));
    const pendingApprovals = approvalCandidates
        .sort((left, right) => (left.request.createdAt ?? 0) - (right.request.createdAt ?? 0))
        .flatMap(({ session, requestId, request }) => {
            const createdAt = request.createdAt ?? now;
            activeKeys.add(approvalKey(session.id, requestId));
            const entry = registry.getOrCreate(session.id, requestId, createdAt, now);
            if (entry.consumed) return [];
            return [{
                sessionId: session.id,
                sessionTitle: sessionTitle(session),
                agent: sessionAgent(session),
                requestId,
                nonce: entry.nonce,
                tool: sanitizeText(request.tool, 80),
                summary: summarizeToolInput(request.tool, request.arguments),
                createdAt,
                expiresAt: entry.expiresAt,
                actions: ['approve', 'deny'] as const,
            }];
        });
    registry.prune(activeKeys, now);
    const approvals = pendingApprovals.slice(0, ROKID_MAX_APPROVALS);

    const summaries = dashboardSessions
        .slice(0, ROKID_MAX_VISIBLE_SESSIONS)
        .map((session) => buildSessionSummary(session, session.id === pinnedSessionId));

    return {
        v: ROKID_PROTOCOL_VERSION,
        type: 'session_state',
        sentAt: now,
        session: buildSessionSummary(
            primary,
            primary.id === pinnedSessionId,
            latestResponses[primary.id],
        ),
        sessions: summaries,
        sessionCount: dashboardSessions.length,
        activeSessionCount: dashboardSessions.filter((session) => (
            session.active && session.presence === 'online'
        )).length,
        approvalCount: pendingApprovals.length,
        approvals,
    };
}

export function latestAgentResponse(messages: readonly Message[]): string | null {
    const message = messages.find((candidate) => (
        candidate.kind === 'agent-text'
        && !candidate.isThinking
        && candidate.text.trim().length > 0
    ));
    return message?.kind === 'agent-text'
        ? sanitizeMultilineText(message.text, 520)
        : null;
}

export function buildSessionSnapshot(
    session: Session,
    registry: ApprovalNonceRegistry,
    now: number,
): RokidSessionSnapshot {
    return buildHappySessionSnapshot({ [session.id]: session }, session.id, null, registry, now);
}

export function buildOfflineSessionSnapshot(now: number): RokidSessionSnapshot {
    return {
        v: ROKID_PROTOCOL_VERSION,
        type: 'session_state',
        sentAt: now,
        session: {
            id: 'none',
            title: 'Happy 세션 없음',
            agent: 'Happy',
            status: 'offline',
            updatedAt: now,
            pinned: false,
        },
        sessions: [],
        sessionCount: 0,
        activeSessionCount: 0,
        approvalCount: 0,
        approvals: [],
    };
}

export function parseDecisionMessage(raw: string): RokidDecisionMessage | null {
    if (raw.length > 4096) return null;
    try {
        const value: unknown = JSON.parse(raw);
        if (!isRecord(value)) return null;
        if (value.v !== ROKID_PROTOCOL_VERSION || value.type !== 'decision') return null;
        if (!isBoundedString(value.sessionId, 200) || !isBoundedString(value.requestId, 300)) return null;
        if (!isBoundedString(value.nonce, 200)) return null;
        if (value.decision !== 'approve' && value.decision !== 'deny') return null;
        if (typeof value.sentAt !== 'number' || !Number.isFinite(value.sentAt)) return null;
        return value as unknown as RokidDecisionMessage;
    } catch {
        return null;
    }
}

export function stableSnapshotFingerprint(snapshot: RokidSessionSnapshot): string {
    return JSON.stringify({ ...snapshot, sentAt: 0 });
}

function summarizeToolInput(tool: string, input: unknown): string {
    const safeInput = redactUnknown(input);
    if (isRecord(safeInput)) {
        const command = safeInput.command;
        if (typeof command === 'string') return sanitizeText(command, 320);
        if (Array.isArray(command)) return sanitizeText(command.join(' '), 320);

        const changes = safeInput.changes;
        if (isRecord(changes)) {
            const files = Object.keys(changes).slice(0, 5);
            const suffix = Object.keys(changes).length > files.length ? ' 외' : '';
            return sanitizeText(`${files.join(', ')}${suffix}`, 320);
        }
    }
    const json = JSON.stringify(safeInput);
    return sanitizeText(json && json !== '{}' ? json : tool, 320);
}

function redactUnknown(value: unknown, depth = 0): unknown {
    if (depth > 4) return '[생략]';
    if (Array.isArray(value)) return value.slice(0, 20).map((item) => redactUnknown(item, depth + 1));
    if (!isRecord(value)) return typeof value === 'string' ? sanitizeText(value, 320) : value;
    return Object.fromEntries(Object.entries(value).slice(0, 30).map(([key, child]) => [
        key,
        isSensitiveKey(key) ? '[가림]' : redactUnknown(child, depth + 1),
    ]));
}

function sanitizeText(value: string, maxLength: number): string {
    const compact = redactSensitiveText(value)
        .replace(/[\r\n\t]+/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    return compact.length <= maxLength ? compact : `${compact.slice(0, maxLength)}…`;
}

function sanitizeMultilineText(value: string, maxLength: number): string {
    const compact = redactSensitiveText(value)
        .replace(/\r\n?/g, '\n')
        .replace(/\t/g, ' ')
        .split('\n')
        .map((line) => line.replace(/[ ]+/g, ' ').trim())
        .join('\n')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
    return compact.length <= maxLength ? compact : `${compact.slice(0, maxLength)}…`;
}

function redactSensitiveText(value: string): string {
    return value
        .replace(/\bsk-[a-zA-Z0-9_-]{12,}\b/g, 'sk-[가림]')
        .replace(/bearer\s+[a-zA-Z0-9._~+/=-]{8,}/gi, 'Bearer [가림]')
        .replace(
            /(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret|authorization)(\s*[:=]\s*)([^\s,;]+)/gi,
            '$1$2[가림]',
        );
}

function isSensitiveKey(key: string): boolean {
    return /token|secret|password|authorization|api.?key|credential/i.test(key);
}

function sessionTitle(session: Session): string {
    const summary = session.metadata?.summary?.text?.trim();
    if (summary) return sanitizeText(summary, 80);
    const path = session.metadata?.path?.replace(/\\/g, '/').replace(/\/$/, '');
    return sanitizeText(path?.split('/').pop() || 'Happy 세션', 80);
}

function sessionAgent(session: Session): string {
    const flavor = session.metadata?.flavor?.toLowerCase();
    if (flavor === 'claude') return 'Claude';
    if (flavor === 'codex' || flavor === 'openai' || flavor === 'gpt') return 'Codex';
    if (flavor === 'gemini') return 'Gemini';
    if (flavor === 'openclaw') return 'OpenClaw';
    return sanitizeText(session.metadata?.flavor || 'Happy', 24);
}

function buildSessionSummary(
    session: Session,
    pinned: boolean,
    latestResponse?: string,
): RokidSessionSummary {
    return {
        id: session.id,
        title: sessionTitle(session),
        agent: sessionAgent(session),
        status: sessionStatus(session, pendingRequestCount(session)),
        updatedAt: Math.max(session.updatedAt, session.thinkingAt, session.activeAt),
        pinned,
        ...(latestResponse ? { latestResponse: sanitizeMultilineText(latestResponse, 520) } : {}),
    };
}

function sessionStatus(session: Session, approvalCount: number): RokidSessionStatus {
    if (!session.active || session.presence !== 'online') return 'offline';
    if (approvalCount > 0) return 'permission_required';
    if (session.thinking) return 'working';
    return 'ready';
}

function pendingRequestCount(session: Session): number {
    return Object.keys(session.agentState?.requests ?? {}).length;
}

function isArchived(session: Session): boolean {
    return session.metadata?.lifecycleState === 'archived';
}

function isDashboardRelevant(session: Session): boolean {
    return !isArchived(session) && (
        session.active
        || session.presence === 'online'
        || pendingRequestCount(session) > 0
    );
}

function compareSessionPriority(left: Session, right: Session): number {
    const leftPermission = pendingRequestCount(left) > 0 ? 1 : 0;
    const rightPermission = pendingRequestCount(right) > 0 ? 1 : 0;
    if (leftPermission !== rightPermission) return rightPermission - leftPermission;
    if (left.thinking !== right.thinking) return Number(right.thinking) - Number(left.thinking);
    const leftOnline = left.active && left.presence === 'online';
    const rightOnline = right.active && right.presence === 'online';
    if (leftOnline !== rightOnline) return Number(rightOnline) - Number(leftOnline);
    if (left.active !== right.active) return Number(right.active) - Number(left.active);
    return Math.max(right.updatedAt, right.thinkingAt, right.activeAt)
        - Math.max(left.updatedAt, left.thinkingAt, left.activeAt);
}

function orderDashboardSessions(sessions: Session[], primary: Session): Session[] {
    return sessions
        .filter((session) => session.id === primary.id || isDashboardRelevant(session))
        .sort((left, right) => {
            if (left.id === primary.id) return -1;
            if (right.id === primary.id) return 1;
            return compareSessionPriority(left, right);
        });
}

function approvalKey(sessionId: string, requestId: string): string {
    return `${sessionId}\u0000${requestId}`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isBoundedString(value: unknown, max: number): value is string {
    return typeof value === 'string' && value.length > 0 && value.length <= max;
}
