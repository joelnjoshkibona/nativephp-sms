<?php

namespace Blutrixx\NativephpSms\Events;

/** Fired in the ephemeral runtime when an outgoing message is reported sent or delivered. */
class SmsStatusChanged
{
    public function __construct(
        public readonly string $clientRef,
        public readonly string $event,   // "sent" | "delivered"
        public readonly bool $ok,
        public readonly int $resultCode,
        public readonly string $to,
        public readonly ?int $rowId,
    ) {}

    public static function fromPayload(array $p): self
    {
        return new self(
            clientRef: (string) ($p['client_ref'] ?? ''),
            event: (string) ($p['event'] ?? ''),
            ok: (bool) ($p['ok'] ?? false),
            resultCode: (int) ($p['result_code'] ?? 0),
            to: (string) ($p['to'] ?? ''),
            rowId: isset($p['row_id']) ? (int) $p['row_id'] : null,
        );
    }
}
