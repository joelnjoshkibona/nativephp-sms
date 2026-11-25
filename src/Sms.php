<?php

namespace Blutrixx\NativephpSms;

/**
 * PHP side of the native bridge. Every method is a thin nativephp_call(); results are the
 * bridge function's own flat map. NOTE: nativephp_call() is not available inside the ephemeral
 * runtime (the headless receivers' PHP hand-off) -- only from the WebView's PHP.
 */
class Sms
{
    /** Role, permission, battery and device state. */
    public function status(): array
    {
        return $this->call('Sms.GetStatus');
    }

    /** Opens the system default-SMS prompt. The outcome arrives as the DefaultSmsResult event (match on $id). */
    public function requestDefault(?string $id = null): array
    {
        return $this->call('Sms.RequestDefault', ['id' => $id]);
    }

    public function listThreads(int $limit = 50, ?int $beforeDate = null): array
    {
        return $this->call('Sms.ListThreads', array_filter(
            ['limit' => $limit, 'before_date' => $beforeDate],
            fn ($v) => $v !== null
        ));
    }

    public function listMessages(int $threadId, int $limit = 50, ?int $beforeId = null): array
    {
        return $this->call('Sms.ListMessages', array_filter(
            ['thread_id' => $threadId, 'limit' => $limit, 'before_id' => $beforeId],
            fn ($v) => $v !== null
        ));
    }

    public function countMessages(): array
    {
        return $this->call('Sms.CountMessages');
    }

    public function markRead(int $threadId): array
    {
        return $this->call('Sms.MarkRead', ['thread_id' => $threadId]);
    }

    public function send(string $to, string $body, ?string $clientRef = null, ?int $subscriptionId = null): array
    {
        return $this->call('Sms.Send', array_filter([
            'to' => $to,
            'body' => $body,
            'client_ref' => $clientRef,
            'subscription_id' => $subscriptionId,
        ], fn ($v) => $v !== null));
    }

    public function listSims(): array
    {
        return $this->call('Sms.ListSims');
    }

    /** @param  string[]  $numbers */
    public function setBlocklist(array $numbers): array
    {
        return $this->call('Sms.SetBlocklist', ['numbers' => array_values($numbers)]);
    }

    public function configure(?bool $ephemeral = null, ?bool $keepAlive = null): array
    {
        return $this->call('Sms.Configure', array_filter(['ephemeral' => $ephemeral, 'keep_alive' => $keepAlive], fn ($v) => $v !== null));
    }

    /** Starts (or resumes) the first-run import of the system store into sms.sqlite; once done, copies only what is new. */
    public function startImport(): array
    {
        return $this->call('Sms.StartImport');
    }

    /** ['state' => none|running|interrupted|done, 'done' => int, 'total' => int, 'oldest_ts' => ms, 'error' => string, ...] */
    public function importStatus(): array
    {
        return $this->call('Sms.ImportStatus');
    }

    /** Opens (and on first use creates) sms.sqlite: ['path' => ..., 'version' => schema version]. */
    public function openStore(): array
    {
        return $this->call('Sms.OpenStore');
    }

    /**
     * Journal events after a cursor, oldest first: ['events' => [...], 'last_id' => int]. Pass $after (an event id), or a
     * $reader to start after what that reader last acknowledged. Nothing is removed; acknowledge with ackEvents().
     */
    public function readEvents(?string $reader = null, ?int $after = null, int $limit = 200): array
    {
        $result = $this->call('Sms.ReadEvents', array_filter(['reader' => $reader, 'after' => $after, 'limit' => $limit], fn ($v) => $v !== null));
        $events = json_decode($result['events_json'] ?? '[]', true);

        return ['events' => is_array($events) ? $events : [], 'last_id' => (int) ($result['last_id'] ?? $after ?? 0)];
    }

    public function ackEvents(string $reader, int $upTo): array
    {
        return $this->call('Sms.AckEvents', ['reader' => $reader, 'up_to' => $upTo]);
    }

    /** The journal events the "drain" reader has not had yet, acknowledged as they are returned (older apps; prefer readEvents). */
    public function drainEvents(): array
    {
        $result = $this->call('Sms.DrainEvents');
        $decoded = json_decode($result['events_json'] ?? '[]', true);

        return is_array($decoded) ? $decoded : [];
    }

    public function takePendingCompose(): array
    {
        return $this->call('Sms.TakePendingCompose');
    }

    public function requestBatteryOptimizationExemption(): array
    {
        return $this->call('Sms.RequestBatteryOptimizationExemption');
    }

    /** The app's notification settings, auto-start screen (Transsion) or Android settings page: notifications|autostart|app. */
    public function openSettings(string $page = 'app'): array
    {
        return $this->call('Sms.OpenSettings', ['page' => $page]);
    }

    /** The number's contact card in Android's contacts, or the new-contact screen with the number filled in. */
    public function openContact(string $address): array
    {
        return $this->call('Sms.OpenContact', ['address' => $address]);
    }

    /** Phone Master's auto-start screen on Transsion phones (Infinix, Tecno, itel), else the app's settings page. */
    public function openAutostartSettings(): array
    {
        return $this->call('Sms.OpenAutostartSettings');
    }

    // Writes to sms.sqlite, which the host app opens read-only. Each answers the written row's id, or
    // ['status' => 'error', 'code' => INVALID|NOT_FOUND|STORE_FAILED, 'message' => a reason the screen can show].

    /** $changes: any of pinned, muted (bool), folder (inbox|spam|archive), color, wallpaper (null clears), read (true). */
    public function saveThread(string $id, array $changes): array
    {
        return $this->call('Sms.SaveThread', ['id' => $id] + array_intersect_key($changes, array_flip(['pinned', 'muted', 'folder', 'color', 'wallpaper', 'read'])));
    }

    public function deleteThread(string $id): array
    {
        return $this->call('Sms.DeleteThread', ['id' => $id]);
    }

    public function deleteMessage(string $id): array
    {
        return $this->call('Sms.DeleteMessage', ['id' => $id]);
    }

    public function saveTemplate(?string $id, string $title, string $body, ?string $shortcut = null): array
    {
        return $this->call('Sms.SaveTemplate', array_filter(['id' => $id, 'title' => $title, 'body' => $body, 'shortcut' => $shortcut], fn ($v) => $v !== null));
    }

    public function deleteTemplate(string $id): array
    {
        return $this->call('Sms.DeleteTemplate', ['id' => $id]);
    }

    /** $fields: thread_id or to, body, send_at (ms), subscription_id, status (waiting|cancelled). */
    public function saveScheduled(?string $id, array $fields): array
    {
        $fields = array_intersect_key($fields, array_flip(['thread_id', 'to', 'body', 'send_at', 'subscription_id', 'status']));

        return $this->call('Sms.SaveScheduled', array_filter(['id' => $id] + $fields, fn ($v) => $v !== null));
    }

    /** Sends a waiting scheduled text now; status sent, failed or skipped. */
    public function sendScheduledNow(string $id): array
    {
        return $this->call('Sms.SendScheduledNow', ['id' => $id]);
    }

    public function deleteScheduled(string $id): array
    {
        return $this->call('Sms.DeleteScheduled', ['id' => $id]);
    }

    /** A key under app.; null removes it. */
    public function setSetting(string $key, ?string $value): array
    {
        return $this->call('Sms.SetSetting', ['key' => $key, 'value' => $value]);
    }

    /** Copies the phone's contacts into sms.sqlite as names for numbers: ['named' => n], or PERMISSION_DENIED. */
    public function syncContacts(): array
    {
        return $this->call('Sms.SyncContacts');
    }

    /** Message ids whose words match $q (each word or its start), newest first. */
    public function search(string $q, int $limit = 100): array
    {
        $result = $this->call('Sms.Search', ['q' => $q, 'limit' => $limit]);
        if (($result['status'] ?? null) === 'error') {
            return $result;
        }
        $hits = json_decode($result['hits_json'] ?? '[]', true);

        return ['hits' => is_array($hits) ? $hits : []];
    }

    public function setActiveThread(?string $id): array
    {
        return $this->call('Sms.SetActiveThread', $id === null ? [] : ['id' => $id]);
    }

    public function takePendingOpen(): array
    {
        return $this->call('Sms.TakePendingOpen');
    }

    /** The private numbers' comparison keys (the last 9 digits): contact suggestions leave them out. */
    public function privateKeys(): array
    {
        $result = $this->call('Sms.PrivateKeys');

        return ($result['status'] ?? null) === 'error' ? [] : array_values($result['keys'] ?? []);
    }

    protected function call(string $function, array $params = []): array
    {
        if (! function_exists('nativephp_call')) {
            return ['status' => 'error', 'code' => 'NO_BRIDGE', 'message' => 'Native bridge not available. Run inside the mobile app.'];
        }

        $result = nativephp_call($function, json_encode((object) $params));

        return $result ? (json_decode($result, true) ?? []) : [];
    }
}
