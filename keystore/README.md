# AutoClash release key

This key is intentionally committed so CI and every future APK use the same
signature. A stable signature is required for in-place updates and for keeping
Shizuku permission and app data.

- File: `keystore/autoclash-release.jks`
- Store / key password: `AutoClash-2026-Release`
- Alias: `autoclash`
- Certificate SHA-256: `67:19:86:75:F1:0E:EB:C0:01:99:A3:3C:50:A2:E0:AA:AA:B4:D8:D5:7E:DC:65:5B:E3:6C:42:8E:98:AD:9D:47`
- Validity: until 2054

Security notice: because this file is public, anyone who can obtain it can
create an APK with the same signature. Only install updates from this
repository's GitHub Releases. For a commercial app, keep the key in CI secrets
instead.
