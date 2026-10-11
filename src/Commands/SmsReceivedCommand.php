<?php

namespace Blutrixx\NativephpSms\Commands;

use Blutrixx\NativephpSms\Events\SmsReceived;
use Illuminate\Console\Command;

/** Run by SmsDeliverReceiver through the ephemeral runtime: `sms:received --payload=<base64 json>`. */
class SmsReceivedCommand extends Command
{
    use DecodesPayload;

    protected $signature = 'sms:received {--payload= : base64-encoded JSON from the native receiver}';

    protected $description = 'Native hand-off: an SMS was received, stored and notified (internal)';

    public function handle(): int
    {
        $payload = $this->payload();
        if ($payload === []) {
            $this->error('empty or malformed payload');

            return self::FAILURE;
        }

        event(SmsReceived::fromPayload($payload));
        $this->info('ok');

        return self::SUCCESS;
    }
}
