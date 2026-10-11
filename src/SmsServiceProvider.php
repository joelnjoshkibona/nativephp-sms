<?php

namespace Blutrixx\NativephpSms;

use Blutrixx\NativephpSms\Commands\SmsReceivedCommand;
use Blutrixx\NativephpSms\Commands\SmsStatusCommand;
use Illuminate\Support\ServiceProvider;

class SmsServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(Sms::class, fn () => new Sms());
    }

    public function boot(): void
    {
        // The native receivers hand off to these through NativePHP's ephemeral runtime
        // (nativeEphemeralArtisan). They must be registered in the console kernel, not only
        // when running in a terminal, so they are not behind runningInConsole().
        $this->commands([
            SmsReceivedCommand::class,
            SmsStatusCommand::class,
        ]);
    }
}
