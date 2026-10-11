<?php

namespace Blutrixx\NativephpSms\Commands;

trait DecodesPayload
{
    /**
     * The native side passes the JSON as base64 in --payload= (JSON does not survive
     * NativePHP's str_getcsv() argument splitting). Returns [] when missing or malformed.
     */
    protected function payload(): array
    {
        $raw = base64_decode((string) $this->option('payload'), true);
        $decoded = $raw === false ? null : json_decode($raw, true);

        return is_array($decoded) ? $decoded : [];
    }
}
