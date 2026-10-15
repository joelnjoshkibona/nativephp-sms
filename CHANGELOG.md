# Changelog

## Unreleased — Phase 0 spike

- Initial plugin: default-SMS role components (SMS_DELIVER, WAP_PUSH_DELIVER stub, SENDTO activity,
  RESPOND_VIA_MESSAGE service), role request (API 26+), receive/store/notify, send with sent/delivered
  callbacks, provider reads, native blocklist, event journal, optional ephemeral-PHP hand-off.
- MMS is deferred: the WAP push receiver is declared (the role requires it) and does nothing.
- Default-SMS checks use `RoleManager.isRoleHeld(ROLE_SMS)` on API 29+. `Telephony.Sms.getDefaultSmsPackage()`
  returned null for the real role holder on the API 36 emulator, so the app reported itself as not default,
  skipped writing sent messages to the system store and refused mark-read.
