# nativephp-sms

A [NativePHP Mobile](https://nativephp.com) plugin that lets an app be the **default SMS app** on Android:
receive, store, notify, send, and read the system SMS store. The Kotlin layer does the work; PHP is optional.

> **Status: Phase 0 spike (v0.1.0).** The Kotlin has not been compiled or run on a device yet.
> MMS is deferred. Minimum Android 8 (API 26).

## Design rule

> Kotlin owns **blocklist check → system-store write → notification → journal**. PHP is optional enrichment.

A `BroadcastReceiver` has no Activity, so it cannot raise a WebView event. Incoming messages are therefore handled
entirely natively and appended to a JSONL journal (`sms_events.jsonl`, 512 KB cap) that the UI drains with
`Sms::drainEvents()`. If enabled, the event is also handed to PHP through the *ephemeral* runtime
(`sms:received`, `sms:status` artisan commands) which fires Laravel events.

The blocklist must be readable without PHP, so it lives in SharedPreferences.

## Install

1. Add the repository and require the package in the host app's `composer.json`:

   ```json
   "repositories": [{ "type": "vcs", "url": "https://github.com/joelnjoshkibona/nativephp-sms.git" }],
   "require": { "blutrixx/nativephp-sms": "dev-main" }
   ```

   then `composer update blutrixx/nativephp-sms`.

2. Register it in `app/Providers/NativeServiceProvider.php`:

   ```php
   public function plugins(): array
   {
       return [\Blutrixx\NativephpSms\SmsServiceProvider::class];
   }
   ```

3. Provide `bootstrap/android/ephemeral.php` (only needed for the PHP hand-off). Model it on `persistent.php`:
   resolve the autoloader from `$_SERVER['COMPOSER_AUTOLOADER_PATH'] ?? $base.'/vendor/autoload.php'`, load
   `bootstrap/app.php`, then call `Native\Mobile\Runtime::boot($app)`.

4. Request the runtime permissions (the plugin lists them in `Sms::status()['permissions']`) with your permission
   helper — they are `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `READ_PHONE_STATE`, `READ_CONTACTS`, `POST_NOTIFICATIONS`.

5. Optional: alias `resources/js/sms.ts` (framework-free bridge wrapper) in Vite, e.g. `'@blutrixx/sms'`.

6. Rebuild the Android app. Plugins are only compiled when listed in `plugins()`.

> Do **not** combine with `nativephp-telephony-gateway` in one app: both start an ephemeral `HandlerThread`, and the
> ephemeral runtime must always be driven from a single thread.

## API

PHP (`Blutrixx\NativephpSms\Facades\Sms`) and JS (`sms` from `resources/js/sms.ts`) mirror each other:

| Method | Purpose |
|---|---|
| `status()` | API level, default-app state, permissions, role availability, ephemeral flag |
| `requestDefault()` | Ask for the SMS role; result arrives as the `DefaultSmsResult` event (JS wrapper correlates by id) |
| `listThreads()` / `listMessages()` / `countMessages()` | Read the system store |
| `markRead()` | Mark a thread read |
| `send()` | Send (multipart-aware, optional SIM); writes to the system store when default |
| `listSims()` | Active subscriptions |
| `setBlocklist()` | Normalised (last 9 digits) numbers blocked natively |
| `configure()` | Toggle the PHP hand-off |
| `drainEvents()` | Read and clear the journal |
| `takePendingCompose()` | Recipient/body from an `sms:` / `smsto:` link |
| `requestBatteryOptimizationExemption()` | Open the system exemption prompt |

Journal event types: `sms_received`, `sms_blocked`, `sms_receive_error`, `send_queued`, `send_failed`, `sms_sent`,
`sms_delivered`, `mms_ignored`, `compose_requested`, `default_sms_result`, `ephemeral_ok`, `ephemeral_failed`, `ephemeral_timeout`.

## Default-SMS manifest

Declared in `nativephp.json`: `SMS_DELIVER` receiver (`BROADCAST_SMS`), `WAP_PUSH_DELIVER` receiver
(`BROADCAST_WAP_PUSH`), `ACTION_SENDTO` activity for `sms/smsto/mms/mmsto`, and the `RESPOND_VIA_MESSAGE` service
(`SEND_RESPOND_VIA_MESSAGE`). The role is requested with `RoleManager` on API 29+ and
`Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` on API 26–28. Truth is always `Telephony.Sms.getDefaultSmsPackage()`.

## Known limits

- MMS received while default is **dropped** (logged as `mms_ignored`).
- Blocked messages are quarantined in plaintext in this version; the vault replaces that with a sealed box.
- OEM battery managers can still kill the process; the exemption helper only opens the system prompt.
- `Telephony.Threads` simple queries vary by ROM; `listThreads` falls back to grouping SMS rows (`approximate: true`).

## Tests

`composer install && vendor/bin/pest` runs structural checks (manifest ↔ Kotlin classes, role components, event
constructor parameters). Behaviour can only be verified on a device or emulator — see the Phase 0 spike plan in the
`sms-messenger` repo (`docs/specs/PHASE_0_SPIKE.md`).
