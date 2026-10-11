<?php

namespace Blutrixx\NativephpSms\Commands;

use Blutrixx\NativephpSms\Events\SmsStatusChanged;
use Illuminate\Console\Command;

/** Run by SmsResultReceiver through the ephemeral runtime: `sms:status --payload=<base64 json>`. */
class SmsStatusCommand extends Command
{
    use DecodesPayload;

    protected $signature = 'sms:status {--payload= : base64-encoded JSON from the native receiver}';

    protected $description = 'Native hand-off: an outgoing SMS was reported sent or delivered (internal)';

    public function handle(): int
    {
        $payload = $this->payload();
        if ($payload === []) {
            $this->error('empty or malformed payload');

            return self::FAILURE;
        }

        event(SmsStatusChanged::fromPayload($payload));
        $this->info('ok');

        return self::SUCCESS;
    }
}
