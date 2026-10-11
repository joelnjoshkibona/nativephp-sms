<?php

namespace Blutrixx\NativephpSms\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array status()
 * @method static array requestDefault(?string $id = null)
 * @method static array listThreads(int $limit = 50, ?int $beforeDate = null)
 * @method static array listMessages(int $threadId, int $limit = 50, ?int $beforeId = null)
 * @method static array countMessages()
 * @method static array markRead(int $threadId)
 * @method static array send(string $to, string $body, ?string $clientRef = null, ?int $subscriptionId = null)
 * @method static array listSims()
 * @method static array setBlocklist(array $numbers)
 * @method static array configure(?bool $ephemeral = null)
 * @method static array drainEvents()
 * @method static array takePendingCompose()
 * @method static array requestBatteryOptimizationExemption()
 *
 * @see \Blutrixx\NativephpSms\Sms
 */
class Sms extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Blutrixx\NativephpSms\Sms::class;
    }
}
