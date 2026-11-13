# AutoClash signing keys

## Release key (current, v2 — rotated 2026-11-12)

The release keystore is **not committed** to this repository.

- Local builds read credentials from gitignored `local.properties`:
  `AC_STORE_FILE`, `AC_STORE_PASSWORD`, `AC_KEY_ALIAS`, `AC_KEY_PASSWORD`.
- CI release builds (`.github/workflows/release.yml`) read credentials from
  GitHub Actions secrets: `ACLASH_STORE_B64` (base64 keystore),
  `ACLASH_STORE_PASSWORD`, `ACLASH_KEY_ALIAS`, `ACLASH_KEY_PASSWORD`.
- Release builds fail loudly when credentials are missing; debug builds fall
  back to AGP's auto-generated default debug keystore.

- Alias: `autoclash`
- Certificate SHA-256: `C7:FB:89:3D:59:1D:78:1C:29:14:C6:F8:73:35:08:CD:7F:32:17:5C:74:97:B9:77:7F:CD:19:4C:01:C1:D6:79`
- Validity: until 2054

In-place updates require the same signature. Devices running an APK signed
with the retired key must uninstall and reinstall once; accessibility,
overlay, notification and Shizuku grants must be re-granted afterwards.

## Retired key (compromised, v1)

The original keystore and its password were committed to this public
repository (they remain recoverable from git history, which is why the key
is treated as compromised rather than merely exposed). It was retired on
2026-11-12. Any APK signed with the retired certificate
(SHA-256 `67:19:86:75:F1:0E:EB:C0:01:99:A3:3C:50:A2:E0:AA:AA:B4:D8:D5:7E:DC:65:5B:E3:6C:42:8E:98:AD:9D:47`)
must not be trusted or installed.
