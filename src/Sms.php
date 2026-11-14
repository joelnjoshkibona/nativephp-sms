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

    /** Phone Master's auto-start screen on Transsion phones (Infinix, Tecno, itel), else the app's settings page. */
    public function openAutostartSettings(): array
    {
        return $this->call('Sms.OpenAutostartSettings');
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
