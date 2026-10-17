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

    /** Returns and clears the native event journal written by the headless receivers. */
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

    protected function call(string $function, array $params = []): array
    {
        if (! function_exists('nativephp_call')) {
            return ['status' => 'error', 'code' => 'NO_BRIDGE', 'message' => 'Native bridge not available. Run inside the mobile app.'];
        }

        $result = nativephp_call($function, json_encode((object) $params));

        return $result ? (json_decode($result, true) ?? []) : [];
    }
}
