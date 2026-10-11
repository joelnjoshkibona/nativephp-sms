<?php

namespace Blutrixx\NativephpSms\Events;

/**
 * Dispatched by native code after the default-SMS prompt closes. Constructor parameter names
 * must match the native payload keys exactly (NativePHP maps them by name).
 */
class DefaultSmsResult
{
    public function __construct(
        public readonly bool $granted,
        public readonly string $default_package = '',
        public readonly int $api = 0,
        public readonly string $reason = '',
        public readonly ?string $id = null,
    ) {}
}
