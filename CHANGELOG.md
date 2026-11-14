# Changelog

## Unreleased: the everyday app (Phase 1, milestone 1.2)

- Names for numbers: `SyncContacts` fills `contact_cache` from the phone's contacts (also when the import ends).
- Search: migration 2 adds an FTS4 index over message words, kept by triggers; `Search` answers ids newest first.
  FTS4 is used because Android's own SQLite has no FTS5.
- Notifications per conversation with Reply and Mark as read from the shade, and a tap that opens the conversation
  (`OpenThreadActivity`, `TakePendingOpen`). A muted conversation, or the one on screen (`SetActiveThread`), posts
  nothing; reading, muting or deleting one clears its notification.

## Unreleased: the app's own store (Phase 1, milestone 1.1)

- `sms.sqlite`, the plugin's own SQLite file beside the host's Laravel database, with Kotlin its only writer: WAL,
  numbered migrations, the plan's tables (threads, messages, attachments, contact_cache, blocklist, spam_senders,
  scheduled, templates, change_log, paired_devices, settings, events). Its schema is `database/sms-schema.sql`.
- Phone-made ULIDs, and `change_log` written by triggers on threads, messages and attachments.
- The journal moves from `sms_events.jsonl` (deleted when drained, half dropped above 512 KB) to the `events` table,
  read with `ReadEvents` and acknowledged per reader with `AckEvents`, so nothing is lost between a read and the app
  keeping it. The old file's lines are copied in once. `DrainEvents` still works.
- `OpenStore`, so a reader can open the file read-only once it exists.
- Receive pipeline: private number, blocklist, spam, normal. Vault texts are sealed with libsodium into
  `vault.sqlite` and reach nothing else; until a key exists they wait in the quarantine file. The blocklist moves
  into `sms.sqlite` with Keystore-encrypted patterns and gains prefix, wildcard and keyword rules.
- Send: one status per message, from one callback per part; `sms_status` journal lines replace `sms_sent` and
  `sms_delivered`. Send returns `message_id` and `thread`, and a repeated `client_ref` does not send twice.
- First-run import (`StartImport`, `ImportStatus`): newest first, resumable, then catch-up of new system rows.
- Writes for a host that reads `sms.sqlite` read-only: `SaveThread`, `DeleteThread`, `DeleteMessage`, `SaveTemplate`,
  `DeleteTemplate`, `SaveScheduled`, `DeleteScheduled`, `SetSetting`.
- `GetStatus` adds `vault_has_key` and `vault_messages`. `VaultSetTestKey` for debug builds.
- `android-tests/`: the Kotlin tested on the JVM with Robolectric (`./gradlew test`), and a `phpunit.xml` so Pest runs.

## Unreleased — Phase 0 spike

- Initial plugin: default-SMS role components (SMS_DELIVER, WAP_PUSH_DELIVER stub, SENDTO activity,
  RESPOND_VIA_MESSAGE service), role request (API 26+), receive/store/notify, send with sent/delivered
  callbacks, provider reads, native blocklist, event journal, optional ephemeral-PHP hand-off.
- MMS is deferred: the WAP push receiver is declared (the role requires it) and does nothing.
- Default-SMS checks use `RoleManager.isRoleHeld(ROLE_SMS)` on API 29+. `Telephony.Sms.getDefaultSmsPackage()`
  returned null for the real role holder on the API 36 emulator, so the app reported itself as not default,
  skipped writing sent messages to the system store and refused mark-read.
- Optional always-on foreground service (`configure(keep_alive: true)`, type `remoteMessaging`) with a silent,
  minimum-importance notification, started on every process start and after boot and app updates. On Infinix (XOS, Griffin power manager)
  swiping a third-party SMS app from recents killed it and Griffin then dropped `SMS_DELIVER`, losing the text
  (Textra behaves the same). With the service running the swipe no longer kills the process and texts arrive.
