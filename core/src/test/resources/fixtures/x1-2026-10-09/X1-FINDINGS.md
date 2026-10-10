# X1 Live Extraction Matrix — 2026-10-09 (evidence)

Source pages (user-provided anime pages, used as extraction testbed):
- veranimes.net  /ver/uchi-no-otouto-domo-ga-sumimasen-14  -> 7 hosts in data-dwn
- animeav1.com   /media/zero-no-tsukaima-futatsuki-no-kishi/1 -> 5 embeds + 3 downloads
- ww3.animeonline.ninja -> Cloudflare JS challenge (needs WebView/cf_clearance; out of JVM scope)

## Matrix results (SourceResolver generic scan, 4/18 resolved)

| host (real link)         | result  | root-cause evidence                                        |
|--------------------------|---------|------------------------------------------------------------|
| mp4upload embed x2       | DIRECT  | html-scan finds a{N}.mp4upload.com:183/d/... CDN link       |
| uqload-static / cdn      | DIRECT  | URL-is-media / content-type                                |
| voe.sx                   | Fatal   | 755B JS shell -> window.location to rotating player domain; player page has obfuscated ["CR1J..." sources array (fixture) + 105KB engine |
| streamwish (dhcplay)    | Fatal   | 819B loading shell + /main.js?v=1.1.9 challenge             |
| filemoon (bysesukior)   | Fatal   | 1972B shell (redirect/challenge)                            |
| mixdrop                 | Fatal   | 10KB eval-obfuscated (MDCore.wurl p.a.c.k.e.r)              |
| doodstream              | Fatal   | obfuscated pass-protocol page                               |
| vidhide (movearnpre)    | Fatal   | connection reset (Network)                                  |
| upnshare / byse         | Fatal   | shells, custom protocol (animeav1.uns.bio/#hash)           |
| page-level extraction   | Fatal   | pages expose options as JS data, not direct media          |

## Conclusion (architecture validation)

The generic scanner (html-scan + QuickJS concat) resolves direct-ish hosts
(mp4upload: 2/2) but every obfuscated host needs a host-specific extractor.
This is live proof for the extractor-cloudkit plan: CloudStream extractors
(Voe redirect-follow + ROT13/base64/shift, StreamWish unpacker, Mixdrop
MDCore.wurl, Filemoon, Doodstream) + extractor-custom for UPNShare/Byse.
Fixtures here are the baseline for extractor unit tests: extractor enters
only with fixture test + live validation (standing rule).
