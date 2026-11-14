# nativephp-sms

A [NativePHP Mobile](https://nativephp.com) plugin that lets an app be the **default SMS app** on Android:
receive, store, notify, send, and read the system SMS store. The Kotlin layer does the work; PHP is optional.

> **Status: Phase 0 spike (v0.1.0).** The Kotlin has not been compiled or run on a device yet.
> MMS is deferred. Minimum Android 8 (API 26).

## Design rule

> Kotlin owns **route (private, blocked, spam, normal) → store or seal → notification → journal**. PHP is optional enrichment.

A `BroadcastReceiver` has no Activity, so it cannot raise a WebView event. Incoming messages are therefore handled
entirely natively and appended to a journal (the `events` table in `sms.sqlite`) that the app reads with
`Sms::readEvents()` and acknowledges with `Sms::ackEvents()`, so an app that dies between the two reads the same events
again instead of losing them. If enabled, the event is also handed to PHP through the *ephemeral* runtime
(`sms:received`, `sms:status` artisan commands) which fires Laravel events.

### Receive pipeline

`ReceivePipeline` routes every incoming text, in this order:

1. **A private number** is sealed into the vault. The notification says "New message" with no name or words, or
   nothing for a silent number.
2. **The blocklist** (number, prefix, wildcard `*` `?`, keyword) is sealed into the vault, with no notification.
   A rule wins over a saved contact.
3. **A reported spammer** (`spam_senders`) goes to `sms.sqlite` with its thread in Spam, and to the system store,
   with no notification.
4. **Everything else** goes to the system store first (so it survives what follows), then `sms.sqlite`, then a
   notification.

The vault is `vault.sqlite`, separate from `sms.sqlite`. Each text is a libsodium sealed box (`crypto_box_seal`) to the
vault's public key, so the receiver seals while the vault is locked and PHP is not running.

A vault text never reaches `sms.sqlite`, the system store, `change_log`, the journal's details, the PHP hand-off or a
notification's text. Until a vault key exists (setup is the app's job), a match waits in a quarantine file and is
sealed when a key is set.

The blocklist lives in `sms.sqlite` with each pattern encrypted under an Android Keystore key, so matching works
without the vault's passcode. The old SharedPreferences set is moved in once.

### Send

`send()` writes the message's row first (`queued`, with its part count), then gives **each part its own** sent and
delivered callback. The callbacks count onto that row, so a message has one status: `queued` → `sent` (every part
sent) → `delivered` (every part delivered), or `failed` as soon as any part fails. A later OK part never undoes a
failure. The journal gets one `sms_status` line per change. A retried `client_ref` answers with the first send's
message instead of sending twice.

### First-run import

`startImport()` copies the system store into `sms.sqlite`: newest first, 500 rows per transaction, on its own thread.
Its cursor is saved with every batch, so an import that is cut short carries on, and a row already copied is
recognised by its `system_id`. Drafts are skipped.

Once the import is done, the same call copies only rows newer than the newest one seen. `status()` starts that
catch-up whenever the app is the default SMS app, which picks up texts another app stored meanwhile.

### sms.sqlite

The plugin's own SQLite file, beside the host app's Laravel database (`app_storage/persisted_data/database/sms.sqlite`,
so PHP finds it as `dirname(DB_DATABASE).'/sms.sqlite'`). **Kotlin is its only writer**; the host app's Laravel opens it
read-only and writes through bridge functions. WAL, `busy_timeout` 5000, foreign keys on.

- Numbered migrations in `SmsDb.MIGRATIONS` (never edit a shipped one). The resulting schema is
  `database/sms-schema.sql`, written by the Kotlin tests, for the host app's PHP tests to build fixture databases.
- Ids are ULIDs made on the phone (`Ulid.kt`), which sort by creation.
- `change_log` (`seq`, `entity`, `entity_id`, `op`, `ts`) is written by **triggers** on `threads`, `messages` and
  `attachments`, so no code path can forget it. `seq` never repeats and is the sync cursor.

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
| `status()` | API level, default-app state, permissions, role availability, ephemeral and keep-alive flags, `transsion` (Infinix, Tecno, itel), `booted_at` and the last boot receiver run (`last_boot_at`, `_reason`, `_keep_alive`), and its last run for a phone start (`last_boot_completed_at`, kept apart so an app update does not erase it): a `last_boot_completed_at` after `booted_at` proves the app starts by itself ; `default_lost_at`: when the boot receiver (a restart or an app update) found the app no longer the default SMS app it had been, and posted "<app> is no longer your SMS app" (cleared, with the notification, once it is the default again) |
| `requestDefault()` | Ask for the SMS role; result arrives as the `DefaultSmsResult` event (JS wrapper correlates by id) |
| `listThreads()` / `listMessages()` / `countMessages()` | Read the system store |
| `markRead()` | Mark a thread read |
| `send()` | Send (multipart-aware, optional SIM): a `sms.sqlite` row with one status (`message_id`, `thread`), and the system store when default |
| `listSims()` | Active subscriptions |
| `setBlocklist()` | The blocked numbers (normalised, encrypted in `sms.sqlite`); other rules are kept |
| `configure()` | Toggle the PHP hand-off |
| `startImport()` / `importStatus()` | The first-run import and its progress (`state`: none, running, interrupted, done; `done`, `total`, `oldest_ts`) |
| `vaultSetTestKey(publicKey)` (JS) | Debug builds only: seal to a test key before vault setup exists |
| `openStore()` | Open (on first use create and migrate) `sms.sqlite`; returns its `path` and schema `version` |
| `readEvents(reader?, after?, limit?)` | Journal events after an event id, or after what `reader` last acknowledged, oldest first, with `last_id`; nothing is removed |
| `ackEvents(reader, upTo)` | Mark the journal as kept by `reader`; events every reader has kept are pruned after 14 days |
| `drainEvents()` | The older call: what the `drain` reader has not had yet, acknowledged as it is returned |
| `takePendingCompose()` | Recipient/body from an `sms:` / `smsto:` link |
| `requestBatteryOptimizationExemption()` | Open the system exemption prompt |
| `openSettings(page)` | `notifications` (the app's notification settings), `autostart` (as below) or `app` (the app's Android page) |
| `openAutostartSettings()` | Phone Master's auto-start screen on Transsion phones, else the app's settings page. On Transsion the app must be allowed there, or nothing runs after a reboot: no keep-alive restart and no texts until the app is opened |

Journal event types:
- receive: `sms_received` (`route`: normal or spam, `message_id`, `thread`, `row_id`: the system store's id), `sms_sealed` (`route`: private or blocked, `sealed`, `notified`: nothing about the sender or the words), `sms_receive_error`, `store_error`, `mms_ignored`;
- send: `send_queued`, `send_failed`, `sms_status` (one per change of a message's status: `message_id`, `status`, `parts`);
- import: `import_started`, `import_done`, `import_caught_up`, `import_failed`;
- other: `compose_requested`, `default_sms_result`, `boot` (`reason`: `boot_completed` or `package_replaced`; `keep_alive`: `off`, `started` or `refused: …`; `is_default`), `default_sms_lost`, `ephemeral_ok`, `ephemeral_failed`, `ephemeral_timeout`.

## Default-SMS manifest

Declared in `nativephp.json`: `SMS_DELIVER` receiver (`BROADCAST_SMS`), `WAP_PUSH_DELIVER` receiver
(`BROADCAST_WAP_PUSH`), `ACTION_SENDTO` activity for `sms/smsto/mms/mmsto`, and the `RESPOND_VIA_MESSAGE` service
(`SEND_RESPOND_VIA_MESSAGE`). The role is requested with `RoleManager` on API 29+ and
`Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` on API 26–28. Truth is always `Telephony.Sms.getDefaultSmsPackage()`.

## Known limits

- MMS received while default is **dropped** (logged as `mms_ignored`).
- Blocked and private texts wait in a plain-text quarantine file until the app sets a vault key.
- OEM battery managers can still kill the process; the exemption helper only opens the system prompt.
- `Telephony.Threads` simple queries vary by ROM; `listThreads` falls back to grouping SMS rows (`approximate: true`).

## Tests

- `composer install && vendor/bin/pest`: structural checks (manifest ↔ Kotlin classes, role components, event
  constructor parameters).
- `cd android-tests && ./gradlew test`: the Kotlin on the JVM with Robolectric (real SQLite): migrations, `change_log`
  triggers, ULIDs, the journal, every receive route, the import, one status per sent message. libsodium does not run
  there: the tests swap in a readable sealer (and a plain AES key for the Keystore), so sealing itself is checked on a
  device. It compiles all of `resources/android` against stand-ins for the NativePHP classes the
  plugin imports (`android-tests/stubs`). After changing `SmsDb.MIGRATIONS`, run it once with `UPDATE_SCHEMA=1` to write
  `database/sms-schema.sql`.
- Receivers, sends and the role need a device or emulator: see `docs/specs/PHASE_0_SPIKE.md` in the `sms-messenger` repo.
