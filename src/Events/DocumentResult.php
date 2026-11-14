<?php

namespace Blutrixx\NativephpSms\Events;

/**
 * Dispatched by native code after the file picker for a backup, the readable export or a restore closes, and the
 * work is done. Parameter names match the native payload keys (NativePHP maps them by name).
 */
class DocumentResult
{
    public function __construct(
        public readonly bool $ok,
        public readonly string $mode = '',
        public readonly string $reason = '',
        public readonly string $name = '',
        public readonly int $bytes = 0,
        public readonly int $count = 0,
        public readonly int $created_at = 0,
        public readonly string $app_version = '',
        public readonly ?string $id = null,
    ) {}
}
