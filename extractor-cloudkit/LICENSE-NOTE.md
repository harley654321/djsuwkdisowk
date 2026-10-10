# extractor-cloudkit — LICENSE NOTE

This module ports host extractors from **recloudstream/cloudstream**
(https://github.com/recloudstream/cloudstream), licensed **GPL-3.0**.

- `VoeExtractor` is a port of `extractors/Voe.kt` (rotating-domain shell
  redirect + decryptF7 chain: rot13, noise-token replacement, base64,
  char-shift, reverse, base64, flat JSON).
- The algorithm was re-verified live against voe.sx on 2026-10-09 (fixtures
  in `src/test/resources/fixtures/`).

Because the code derives from GPL-3.0 work, **this module is GPL-3.0** and
must stay a separate Gradle module from the MIT `:core`. Apps that do not
depend on `extractor-cloudkit` are not affected by its license.

Upstream sync: the CI `sync-cloudkit` job (planned) diffs the recloudstream
extractors folder and opens a PR with the ports that changed, so community
fixes land here within hours without manual maintenance.
