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
    /** Android 13+, installed from a file: the SMS role waits until "Allow restricted settings" in App info. */
    restricted_settings?: boolean
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
    /** Infinix, Tecno or itel: the app must be allowed in Phone Master's auto-start screen (openAutostartSettings) */
    transsion: boolean
    blocklist_size: number
    /** A vault public key exists (vault setup, or the debug test key): blocked and private texts are sealed, not quarantined. */
    vault_has_key: boolean
    vault_messages: number
    ephemeral_enabled: boolean
    keep_alive_enabled: boolean
    keep_alive_running: boolean
    /** when the phone last started, epoch ms */
    booted_at: number
    /** when the boot receiver last ran (0: never), why, and what keep-alive did then */
    last_boot_at: number
    last_boot_reason: '' | 'boot_completed' | 'package_replaced'
    last_boot_keep_alive: string
    /** when the boot receiver last ran for a phone start (BOOT_COMPLETED; 0: never): after `booted_at`, the app starts by itself */
    last_boot_completed_at: number
    /** when the app was found to have lost the SMS role it once held (0: it holds it, or never did); the plugin also notifies */
    default_lost_at: number
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
    /** The message in sms.sqlite (one status for every part: queued, sent, delivered, failed) and its thread. */
    message_id: string
    thread: string
    /** The client_ref had been sent already: this is that message, not a second send. */
    duplicate: boolean
}

export interface VaultConversation {
    address: string
    name: string | null
    /** private: a private number; blocked: a blocked sender; hidden: hidden messages */
    tag: 'private' | 'blocked' | 'hidden'
    notify: 'generic' | 'silent' | null
    last_at: number
    last_body: string
    last_out: boolean
    count: number
}

export interface VaultMessage {
    id: string
    why: 'private' | 'blocked' | 'hidden'
    direction: 'in' | 'out'
    address: string
    body: string
    ts: number
    status: string
}

export interface VaultRule {
    id: string
    kind: 'number' | 'prefix' | 'wildcard' | 'keyword'
    action: 'block' | 'private'
    notify: 'generic' | 'silent' | null
    pattern: string
}

/** The file picker's outcome for a backup, the readable export or a restore (no time limit: the person may browse). */
export interface DocumentResult {
    ok: boolean
    mode: 'backup' | 'readable' | 'open'
    /** cancelled, no_vault, not_a_backup, no_picker, or the failure's class */
    reason?: string
    name?: string
    bytes?: number
    /** readable: how many messages */
    count?: number
    /** open: when the backup was made */
    created_at?: number
    app_version?: string
}

function pickDocument(fn: string): Promise<DocumentResult> {
    listen()
    const id = `doc-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    return new Promise<DocumentResult>((resolve, reject) => {
        pendingDocs.set(id, { resolve })
        bridgeCall(fn, { id }).catch((err) => {
            pendingDocs.delete(id)
            reject(err)
        })
    })
}

export interface SmsImportStatus {
    /** none: never started; interrupted: started and the app was killed (StartImport carries on) */
    state: 'none' | 'running' | 'interrupted' | 'done'
    running: boolean
    done: number
    total: number
    /** the date of the oldest text copied so far (the import goes newest first) */
    oldest_ts: number
    started_at: number
    finished_at: number
    error: string
}

export interface SmsJournalEvent {
    /** The journal row's id (sms.sqlite events), the cursor for readEvents and ackEvents. */
    id?: number
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

export interface PairedBrowser {
    id: string
    name: string
    paired_at: number
    last_seen: number
}

const pendingRole = new Map<string, { resolve: (r: DefaultSmsResult) => void; timer: ReturnType<typeof setTimeout> }>()
const pendingDocs = new Map<string, { resolve: (r: DocumentResult) => void }>()
let listening = false

function listen() {
    if (listening) return
    listening = true
    document.addEventListener('native-event', (e: Event) => {
        const detail = (e as CustomEvent).detail
        const name = String(detail?.event ?? '')
        const isDoc = name.endsWith('Events\\DocumentResult')
        if (!isDoc && !name.endsWith('Events\\DefaultSmsResult')) return
        let payload = detail.payload
        if (typeof payload === 'string') {
            try { payload = JSON.parse(payload) } catch { return }
        }
        if (isDoc) {
            const doc = payload?.id ? pendingDocs.get(payload.id) : undefined
            if (!doc) return
            pendingDocs.delete(payload.id)
            doc.resolve(payload as DocumentResult)
            return
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

    /** The active SIMs, and the phone's own default SIM for texts (-1: it asks each time, or cannot say). */
    listSims: () =>
        bridgeCall<{ sims: { slot: number; subscription_id: number; phone_number: string; operator: string }[]; default_subscription_id: number }>('Sms.ListSims'),

    setBlocklist: (numbers: string[]) => bridgeCall<{ size: number }>('Sms.SetBlocklist', { numbers }),

    configure: (opts: { ephemeral?: boolean; keep_alive?: boolean }) =>
        bridgeCall<{ ephemeral_enabled: boolean; keep_alive_enabled: boolean }>('Sms.Configure', opts),

    /** Returns and clears the native event journal (receive / send / status / compose / role). */
    /** Copies the phone's contacts into sms.sqlite as names for numbers. */
    syncContacts: () => bridgeCall<{ named: number }>('Sms.SyncContacts'),

    /** Messages whose words match q (each word or its start), newest first. */
    async search(q: string, limit = 100): Promise<{ id: string; thread_id: string; ts: number }[]> {
        const result = await bridgeCall<{ hits_json: string }>('Sms.Search', { q, limit })
        try { return JSON.parse(result.hits_json || '[]') } catch { return [] }
    },

    /** The conversation on screen (null: none): its texts post no notification. */
    setActiveThread: (id: string | null) => bridgeCall<{ active: string }>('Sms.SetActiveThread', id ? { id } : {}),

    /** The conversation a tapped notification asked to open, once ('' when none). */
    takePendingOpen: () => bridgeCall<{ thread: string }>('Sms.TakePendingOpen'),

    // -- backup and export (2.1) ----------------------------------------------------------------------------------------

    /** Save a sealed backup file where the person picks (needs a vault: NO_VAULT otherwise). */
    exportBackup: () => pickDocument('Sms.BackupExport'),
    /** Save the normal texts as a readable zip (messages.json, messages.txt); never the vault. */
    exportReadable: () => pickDocument('Sms.ExportReadable'),
    /** Pick a backup file; its date arrives in the result. Then restoreBackup with the passphrase or recovery key. */
    pickBackup: () => pickDocument('Sms.BackupPick'),
    restoreBackup: (typed: string) => bridgeCall<{ vault_rows: number; rules: number; settings: number; conversations: number }>('Sms.BackupRestore', { typed }),
    backupState: () => bridgeCall<{ vault: boolean; last_file_at: number }>('Sms.BackupState'),

    // -- paired browsers (2.4) --
    /** Pairs with the browser whose QR code was scanned. Errors: BAD_CODE, EXPIRED, OTHER_ACCOUNT, MISMATCH, OFFLINE. */
    relayPair: (code: string) => bridgeCall<PairedBrowser>('Sms.RelayPair', { code }),
    /** Pairs by the short code typed from the browser's screen: answers the six digits to compare on both screens. */
    relayPairCode: (server: string, code: string) => bridgeCall<{ id: string; name: string; digits: string }>('Sms.RelayPairCode', { server, code }),
    /** The digits matched (pairs) or not (removes the browser). */
    relayConfirm: (id: string, match: boolean) => bridgeCall<{ paired: boolean }>('Sms.RelayConfirm', { id, match }),
    relayBrowsers: () => bridgeCall<{ browsers: PairedBrowser[]; state: 'off' | 'connecting' | 'connected'; server: string }>('Sms.RelayBrowsers'),
    relayUnpair: (id: string) => bridgeCall<{ removed: string }>('Sms.RelayUnpair', { id }),
    relaySelfTest: () => bridgeCall<{ ok: boolean; failed: string }>('Sms.RelaySelfTest'),

    // -- the vault (1.3) ------------------------------------------------------------------------------------------------

    /** Whether a vault is set up, and open now. */
    vaultState: () => bridgeCall<{ exists: boolean; open: boolean }>('Sms.VaultState'),
    /** Makes the vault; answers the recovery key, the only time it is shown. Leaves it open. */
    vaultSetup: (passphrase: string) => bridgeCall<{ recovery_key: string }>('Sms.VaultSetup', { passphrase }),
    /** Tries the passphrase or the recovery key; always takes the same time. */
    vaultUnlock: (typed: string) => bridgeCall<{ open: boolean }>('Sms.VaultUnlock', { typed }),
    vaultLock: () => bridgeCall<{ open: boolean }>('Sms.VaultLock'),
    vaultChangePassphrase: (passphrase: string) => bridgeCall<{ changed: boolean }>('Sms.VaultChangePassphrase', { passphrase }),
    vaultWipe: () => bridgeCall<{ wiped: boolean }>('Sms.VaultWipe'),
    async vaultConversations(): Promise<{ conversations: VaultConversation[]; rules: number }> {
        const r = await bridgeCall<{ conversations_json: string; rules: number }>('Sms.VaultConversations')
        return { conversations: JSON.parse(r.conversations_json || '[]'), rules: r.rules }
    },
    async vaultMessages(address: string): Promise<VaultMessage[]> {
        const r = await bridgeCall<{ messages_json: string }>('Sms.VaultMessages', { address })
        return JSON.parse(r.messages_json || '[]')
    },
    hideMessages: (ids: string[]) => bridgeCall<{ hidden: number }>('Sms.HideMessages', { ids }),
    hideThread: (id: string) => bridgeCall<{ hidden: number }>('Sms.HideThread', { id }),
    vaultRestore: (ids: string[]) => bridgeCall<{ restored: number }>('Sms.VaultRestore', { ids }),
    vaultDelete: (ids: string[]) => bridgeCall<{ deleted: number }>('Sms.VaultDelete', { ids }),
    vaultDeleteConversation: (address: string) => bridgeCall<{ deleted: number }>('Sms.VaultDeleteConversation', { address }),
    vaultSend: (to: string, body: string, subscriptionId?: number | null, clientRef?: string) =>
        bridgeCall<{ client_ref: string }>('Sms.VaultSend', { to, body, subscription_id: subscriptionId ?? undefined, client_ref: clientRef }),
    block: (number: string, threadId?: string, move = false) => bridgeCall<{ moved: number }>('Sms.Block', { number, thread_id: threadId, move }),
    keepPrivate: (threadId: string, notify: 'generic' | 'silent' = 'generic') => bridgeCall<{ moved: number }>('Sms.KeepPrivate', { thread_id: threadId, notify }),
    setPrivateNotify: (address: string, notify: 'generic' | 'silent') => bridgeCall<{ notify: string }>('Sms.SetPrivateNotify', { address, notify }),
    moveOutOfVault: (address: string) => bridgeCall<{ restored: number }>('Sms.MoveOutOfVault', { address }),
    async rules(): Promise<VaultRule[]> {
        const r = await bridgeCall<{ rules_json: string }>('Sms.Rules')
        return JSON.parse(r.rules_json || '[]')
    },
    addRule: (kind: VaultRule['kind'], pattern: string) => bridgeCall<{ id: string }>('Sms.AddRule', { kind, pattern }),
    removeRule: (id: string) => bridgeCall<{ kind: string; pattern: string }>('Sms.RemoveRule', { id }),
    privateKeys: () => bridgeCall<{ keys: string[] }>('Sms.PrivateKeys'),
    reportSpam: (threadId: string) => bridgeCall<{ spam: boolean }>('Sms.ReportSpam', { thread_id: threadId }),
    notSpam: (threadId: string) => bridgeCall<{ spam: boolean }>('Sms.NotSpam', { thread_id: threadId }),
    purgeSpam: () => bridgeCall<{ purged: number }>('Sms.PurgeSpam'),
    /** Debug builds only: libsodium on this phone, and Argon2id's time. */
    vaultSelfTest: () => bridgeCall<{ seal_open: boolean; wrong_key_fails: boolean; argon2id_ms: number; secretbox: boolean }>('Sms.VaultSelfTest'),

    /** Starts (or resumes) the first-run import of the system store; once done, copies only what is new. */
    startImport: () => bridgeCall<SmsImportStatus & { started: boolean }>('Sms.StartImport'),

    importStatus: () => bridgeCall<SmsImportStatus>('Sms.ImportStatus'),

    /** Debug builds only: seal the vault to a test public key (base64 of 32 bytes) before vault setup exists. */
    vaultSetTestKey: (publicKey: string) => bridgeCall<{ vault_messages: number }>('Sms.VaultSetTestKey', { public_key: publicKey }),

    /** Opens (and on first use creates) sms.sqlite. */
    openStore: () => bridgeCall<{ path: string; version: number }>('Sms.OpenStore'),

    /** Journal events after `after`, or after what `reader` last acknowledged; nothing is removed. */
    async readEvents(opts: { reader?: string; after?: number; limit?: number }): Promise<{ events: SmsJournalEvent[]; lastId: number }> {
        const result = await bridgeCall<{ events_json: string; last_id: number }>('Sms.ReadEvents', opts)
        let events: SmsJournalEvent[] = []
        try { events = JSON.parse(result.events_json || '[]') } catch { /* an unreadable page is an empty one */ }
        return { events, lastId: Number(result.last_id ?? opts.after ?? 0) }
    },

    /** Marks the journal as kept by `reader` up to `upTo`. */
    ackEvents: (reader: string, upTo: number) => bridgeCall<{ reader: string; acked: number }>('Sms.AckEvents', { reader, up_to: upTo }),

    async drainEvents(): Promise<SmsJournalEvent[]> {
        const result = await bridgeCall<{ events_json: string }>('Sms.DrainEvents')
        try { return JSON.parse(result.events_json || '[]') } catch { return [] }
    },

    takePendingCompose: () =>
        bridgeCall<{ pending: boolean; to?: string; body?: string }>('Sms.TakePendingCompose'),

    requestBatteryOptimizationExemption: () =>
        bridgeCall<{ launched: boolean }>('Sms.RequestBatteryOptimizationExemption'),

    /** The app's notification settings, auto-start screen (Transsion) or Android settings page. */
    openSettings: (page: 'notifications' | 'autostart' | 'app') =>
        bridgeCall<{ launched: boolean; screen: 'notifications' | 'autostart' | 'app_details' }>('Sms.OpenSettings', { page }),

    /** Phone Master's auto-start screen on Transsion phones, else the app's settings page. */
    openAutostartSettings: () =>
        bridgeCall<{ launched: boolean; screen: 'autostart' | 'app_details' }>('Sms.OpenAutostartSettings'),
}

export default sms
