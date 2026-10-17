/**
 * JS wrapper for the nativephp-sms bridge. No framework or package imports on purpose, so it
 * can be aliased as '@blutrixx/sms' from vendor/ and used from any Vue app.
 *
 *   import { sms } from '@blutrixx/sms'
 *   const status = await sms.status()
 *   const { granted } = await sms.requestDefault()
 */

const ENDPOINT = '/_native/api/call'

export interface SmsStatus {
    api: number
    manufacturer: string
    model: string
    package: string
    default_package: string
    is_default: boolean
    role_available: boolean
    role_held: boolean
    uses_role_manager: boolean
    permissions: Record<string, boolean>
    notifications_enabled: boolean
    battery_exempt: boolean
    blocklist_size: number
    ephemeral_enabled: boolean
    keep_alive_enabled: boolean
    keep_alive_running: boolean
}

export interface SmsThread {
    thread_id: number
    date: number
    message_count: number
    addresses: string[]
    snippet: string
    read: boolean
    /** true when the provider's thread query was unavailable and counts come from grouping SMS rows */
    approximate: boolean
}

export interface SmsMessage {
    id: number
    thread_id: number
    address: string
    body: string
    date: number
    date_sent: number
    /** Telephony.Sms.MESSAGE_TYPE_*: 1 inbox, 2 sent, 3 draft, 4 outbox, 5 failed, 6 queued */
    type: number
    read: boolean
    /** Telephony.Sms.STATUS_*: -1 none, 0 complete, 32 pending, 64 failed */
    status: number
    subscription_id: number
}

export interface SmsSendResult {
    client_ref: string
    queued: boolean
    /** false when the app is not the default SMS app: the text was sent but not written to the system store */
    stored: boolean
    row_id: number
    thread_id: number
    parts: number
}

export interface SmsJournalEvent {
    type: string
    at: number
    data: Record<string, unknown>
}

export interface DefaultSmsResult {
    granted: boolean
    default_package: string
    api: number
    reason: string
    id?: string
}

export class SmsBridgeError extends Error {
    constructor(public code: string, message: string) {
        super(message)
    }
}

async function bridgeCall<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    const csrf = document.querySelector<HTMLMetaElement>('meta[name="csrf-token"]')?.content || ''
    const response = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
        body: JSON.stringify({ method, params }),
    })
    const result = await response.json()
    if (result.status === 'error') {
        throw new SmsBridgeError(result.code || 'NATIVE_ERROR', result.message || 'Native call failed')
    }
    // The route wraps the bridge map in { status, data }; some builds nest once more under data.data.
    const payload = result.data
    const inner = payload && payload.data !== undefined && payload.status === undefined ? payload.data : payload
    if (inner && inner.status === 'error') {
        throw new SmsBridgeError(inner.code || 'NATIVE_ERROR', inner.message || 'Native call failed')
    }
    return inner as T
}

const pendingRole = new Map<string, { resolve: (r: DefaultSmsResult) => void; timer: ReturnType<typeof setTimeout> }>()
let listening = false

function listen() {
    if (listening) return
    listening = true
    document.addEventListener('native-event', (e: Event) => {
        const detail = (e as CustomEvent).detail
        if (!detail?.event || !String(detail.event).endsWith('Events\\DefaultSmsResult')) return
        let payload = detail.payload
        if (typeof payload === 'string') {
            try { payload = JSON.parse(payload) } catch { return }
        }
        const id = payload?.id as string | undefined
        const pending = id ? pendingRole.get(id) : undefined
        if (!pending) return
        clearTimeout(pending.timer)
        pendingRole.delete(id as string)
        pending.resolve(payload as DefaultSmsResult)
    })
}

export const sms = {
    status: () => bridgeCall<SmsStatus>('Sms.GetStatus'),

    /** Opens the system prompt; resolves with the real outcome (or rejects after timeoutMs with no answer). */
    requestDefault(timeoutMs = 120_000): Promise<DefaultSmsResult> {
        listen()
        const id = `role-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
        return new Promise<DefaultSmsResult>((resolve, reject) => {
            const timer = setTimeout(() => {
                pendingRole.delete(id)
                reject(new SmsBridgeError('TIMEOUT', 'No answer from the default-SMS prompt'))
            }, timeoutMs)
            pendingRole.set(id, { resolve, timer })
            bridgeCall('Sms.RequestDefault', { id }).catch((err) => {
                clearTimeout(timer)
                pendingRole.delete(id)
                reject(err)
            })
        })
    },

    listThreads: (limit = 50, beforeDate?: number) =>
        bridgeCall<{ threads: SmsThread[]; count: number; ms: number }>('Sms.ListThreads', { limit, before_date: beforeDate }),

    listMessages: (threadId: number, limit = 50, beforeId?: number) =>
        bridgeCall<{ messages: SmsMessage[]; count: number; ms: number }>('Sms.ListMessages', {
            thread_id: threadId, limit, before_id: beforeId,
        }),

    countMessages: () => bridgeCall<{ count: number; ms: number }>('Sms.CountMessages'),

    markRead: (threadId: number) => bridgeCall<{ updated: number }>('Sms.MarkRead', { thread_id: threadId }),

    send: (to: string, body: string, subscriptionId?: number, clientRef?: string) =>
        bridgeCall<SmsSendResult>('Sms.Send', { to, body, subscription_id: subscriptionId, client_ref: clientRef }),

    listSims: () =>
        bridgeCall<{ sims: { slot: number; subscription_id: number; phone_number: string; operator: string }[] }>('Sms.ListSims'),

    setBlocklist: (numbers: string[]) => bridgeCall<{ size: number }>('Sms.SetBlocklist', { numbers }),

    configure: (opts: { ephemeral?: boolean; keep_alive?: boolean }) =>
        bridgeCall<{ ephemeral_enabled: boolean; keep_alive_enabled: boolean }>('Sms.Configure', opts),

    /** Returns and clears the native event journal (receive / send / status / compose / role). */
    async drainEvents(): Promise<SmsJournalEvent[]> {
        const result = await bridgeCall<{ events_json: string }>('Sms.DrainEvents')
        try { return JSON.parse(result.events_json || '[]') } catch { return [] }
    },

    takePendingCompose: () =>
        bridgeCall<{ pending: boolean; to?: string; body?: string }>('Sms.TakePendingCompose'),

    requestBatteryOptimizationExemption: () =>
        bridgeCall<{ launched: boolean }>('Sms.RequestBatteryOptimizationExemption'),
}

export default sms
