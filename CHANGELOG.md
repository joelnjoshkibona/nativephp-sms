# Changelog

## Unreleased — Phase 0 spike

- Initial plugin: default-SMS role components (SMS_DELIVER, WAP_PUSH_DELIVER stub, SENDTO activity,
  RESPOND_VIA_MESSAGE service), role request (API 26+), receive/store/notify, send with sent/delivered
  callbacks, provider reads, native blocklist, event journal, optional ephemeral-PHP hand-off.
- MMS is deferred: the WAP push receiver is declared (the role requires it) and does nothing.
