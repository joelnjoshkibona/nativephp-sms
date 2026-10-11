<?php

namespace Blutrixx\NativephpSms\Events;

/**
 * Laravel event fired inside the ephemeral PHP runtime after the native receiver has ALREADY
 * stored and notified the message. Listen to it to enrich/sync; never rely on it for delivery.
 * The body is deliberately not in the payload (only its length): read it from the system store.
 */
class SmsReceived
{
    public function __construct(
        public readonly string $from,
        public readonly int $bodyLength,
        public readonly int $parts,
        public readonly bool $stored,
        public readonly ?int $rowId,
        public readonly ?int $threadId,
        public readonly bool $notified,
        public readonly ?int $slot,
        public readonly int $subscriptionId,
        public readonly int $receivedAt,
    ) {}

    public static function fromPayload(array $p): self
    {
        return new self(
            from: (string) ($p['from'] ?? ''),
            bodyLength: (int) ($p['body_len'] ?? 0),
            parts: (int) ($p['parts'] ?? 1),
            stored: (bool) ($p['stored'] ?? false),
            rowId: isset($p['row_id']) ? (int) $p['row_id'] : null,
            threadId: isset($p['thread_id']) ? (int) $p['thread_id'] : null,
            notified: (bool) ($p['notified'] ?? false),
            slot: isset($p['slot']) ? (int) $p['slot'] : null,
            subscriptionId: (int) ($p['sub_id'] ?? -1),
            receivedAt: (int) ($p['native_received_at'] ?? 0),
        );
    }
}
