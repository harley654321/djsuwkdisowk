# extractor-cloudkit — LICENSE NOTE

This module ports host extractors from **recloudstream/cloudstream**
(https://github.com/recloudstream/cloudstream), licensed **GPL-3.0**.

Ports in this module (upstream file → port):

- `extractors/Voe.kt` → `VoeExtractor` (rotating-domain shell redirect
  + decryptF7 chain: rot13, noise-token replacement, base64,
  char-shift, reverse, base64, flat JSON).
- `extractors/MixDrop.kt` → `MixDropExtractor` (packed MDCore + wurl).
- `extractors/DoodExtractor.kt` → `DoodExtractor` (pass_md5 handshake).
- `extractors/StreamWishExtractor.kt` → `StreamWishExtractor` (packed /
  jwplayer player pages) and `Packer`.
- `extractors/Uqload.kt` → `UqloadExtractor` (sources regex).
- `extractors/ByseSX.kt` → `ByseExtractor` (API envelope + AES-256-GCM;
  live capture 2026-10-10 proved the key scheme is version-keyed, past
  upstream's concat).
- `extractors/VidHidePro.kt` → `VidHideExtractor` (player-path rewrite +
  jwplayer/packed scan).
- `extractors/VidStack.kt` → `UpnshareExtractor` (hex payload +
  AES-128-CBC).

Each port was re-verified against a live capture from the user's real
pages (fixtures in `src/test/resources/fixtures/`, live evidence dated
in each extractor's KDoc).

Because the code derives from GPL-3.0 work, **this module is GPL-3.0** and
must stay a separate Gradle module from the MIT `:core`. Apps that do not
depend on `extractor-cloudkit` are not affected by its license.

Upstream sync: `upstream-manifest.txt` pins the exact upstream state
(sha256 per file) each port was verified against, and the weekly CI
`sync-cloudkit` job re-downloads those files and fails loudly on drift —
the red run is the signal to re-port and re-verify the affected
extractor.
