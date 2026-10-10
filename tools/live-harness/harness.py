#!/usr/bin/env python3
"""harness.py — matriz live de URLs de video desde las APIs de extensiones.

Herramienta de pruebas (no forma parte de la librería distribuida). Replica
la lógica de las extensiones de anime en español (Kohi-den/extensions-source,
formato aniyomi/kiyomi) para enumerar episodios y servidores frescos, sin
necesidad de ir a páginas al azar.

Uso:
    python3 tools/live-harness/harness.py [--out matrix.csv] [--animes N]

Salida: CSV con columnas provider,anime,episode,server,url — la matriz live
que alimenta los smoke tests de extractor-cloudkit y el ciclo completo
extracción + descarga.

Origen de cada proveedor (código de la extensión de donde se extrajo la
lógica, ver docs en tools/live-harness/README.es.md):
    animeav1       src/es/animeav1/AnimeAv1.kt
    veranimes      src/es/veranimes/VerAnimes.kt
    monoschinos    src/es/monoschinos/MonosChinos.kt
    tioanime       src/es/tioanimeh/TioanimeH.kt
    animelatinohd  src/es/animelatinohd/AnimeLatinoHD.kt
"""
import argparse
import base64
import csv
import json
import re
import ssl
import subprocess
import sys
import urllib.error
import urllib.request
from html import unescape
from urllib.parse import urljoin

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
CTX = ssl.create_default_context()
TIMEOUT = 25


def http_get(url, referer=None, extra_headers=None):
    """GET con UA de navegador vía curl; devuelve (texto_final, url_final)."""
    cmd = ["curl", "-sL", "--compressed", "--max-time", "30", "-A", UA,
           "-H", "Accept-Language: es-419,es;q=0.8",
           "-w", "\n__CURL__%{url_effective}"]
    if referer:
        cmd += ["-H", f"Referer: {referer}"]
    if extra_headers:
        cmd += sum([["-H", f"{k}: {v}"] for k, v in extra_headers.items()], [])
    cmd.append(url)
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=45).stdout
    if "__CURL__" not in out:
        raise URLError(f"sin respuesta: {url}")
    body, final = out.rsplit("\n__CURL__", 1)
    return body, final.strip()


def http_post(url, body, referer=None, extra_headers=None):
    """POST x-www-form-urlencoded vía curl; devuelve (texto, url_final)."""
    cmd = ["curl", "-sL", "--compressed", "--max-time", "30", "-A", UA,
           "-H", "Content-Type: application/x-www-form-urlencoded; charset=UTF-8",
           "-H", "X-Requested-With: XMLHttpRequest",
           "-w", "\n__CURL__%{url_effective}",
           "--data", body]
    if referer:
        cmd += ["-H", f"Referer: {referer}"]
    else:
        cmd += ["-H", f"Referer: {urljoin(url, '/')}"]
    if extra_headers:
        cmd += sum([["-H", f"{k}: {v}"] for k, v in extra_headers.items()], [])
    cmd.append(url)
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=45).stdout
    if "__CURL__" not in out:
        raise URLError(f"sin respuesta: {url}")
    resp, final = out.rsplit("\n__CURL__", 1)
    return resp, final.strip()


# ---------------------------------------------------------------- providers

def av1_list(base):
    """animeav1: catálogo latest → enlaces /media/<slug> (article a)."""
    html, _ = http_get(f"{base}/catalogo?order=latest_released&page=1")
    return re.findall(r'href="(/media/[^"#?]+)"', html)


def av1_episode(base, anime_path):
    """animeav1: script episodes:[{id,number}...] en la página del anime."""
    html, _ = http_get(urljoin(base, anime_path))
    m = re.search(r"episodes\s*:\s*\[([^\]]*)\]", html)
    eps = re.findall(r"number\s*:\s*([0-9.]+)", m.group(1)) if m else []
    return [urljoin(base, f"{anime_path}/{n}") for n in eps[-1:]]


def av1_servers(base, ep_url):
    """animeav1: arrays SUB/DUB {server,url} en la página del episodio."""
    html, _ = http_get(ep_url)
    pat = re.compile(r'\{\s*server\s*:\s*"([^"]*)"\s*,\s*url\s*:\s*"([^"]*)"\s*\}')
    out = []
    for block in re.findall(r"(?:SUB|DUB)\s*:\s*\[(.*?)\]", html, re.S):
        for label, url in pat.findall(block):
            url = url.replace("\\/", "/").split("?embed")[0]
            out.append((label, url))
    return out


def veranimes_list(base):
    """veranimes: /animes?orden=desc → href de animes."""
    html, final = http_get(f"{base}/animes?orden=desc&pag=1")
    links = re.findall(r'href="(https?://[^"]*/anime/[^"#?]+)"', html)
    if not links:
        links = [u for u in re.findall(r'href="([^"#?]+)"', html)
                 if re.search(r"/anime/[^/]+$", u)]
    return [urljoin(final, l) for l in links]


def veranimes_episode(base, anime_url):
    """veranimes: script var eps=[...] + attr data-sl → /ver/<slug>-<n>."""
    html, final = http_get(anime_url)
    m_eps = re.search(r"var\s+eps\s*=\s*(\[[^\]]*\])", html)
    m_sl = re.search(r'data-sl="([^"]+)"', html)
    if not (m_eps and m_sl):
        return []
    try:
        eps = json.loads(m_eps.group(1))
    except json.JSONDecodeError:
        return []
    return [urljoin(final, f"/ver/{m_sl.group(1)}-{eps[-1]}")] if eps else []


def veranimes_servers(base, ep_url):
    """veranimes: POST /process acc=opt&i=<data-encrypt> → li encrypt=hex."""
    html, final = http_get(ep_url)
    m = re.search(r'data-encrypt="([^"]+)"', html)
    if not m:
        return []
    body, _ = http_post(f"{base}/process", f"acc=opt&i={m.group(1)}",
                        referer=final,
                        extra_headers={"Accept": "*/*"})
    out = []
    for enc in re.findall(r'encrypt="([0-9a-fA-F]+)"', body):
        try:
            url = bytes.fromhex(enc).decode("utf-8", "replace")
            out.append(("", url))
        except ValueError:
            pass
    return out


def monoschinos_list(base):
    """monoschinos: /animes?estado=en+emision → hrefs de animes."""
    html, final = http_get(f"{base}/animes?estado=en+emision&pag=1")
    links = [u for u in re.findall(r'href="([^"]+)"', html)
             if re.search(r"/anime/[^/]+/?$", u) and "animes?" not in u]
    return [urljoin(final, l) for l in links]


def monoschinos_episode(base, anime_url):
    """monoschinos: enlaces de episodio en la página del anime."""
    html, final = http_get(anime_url)
    root = re.match(r"(https?://[^/]+)", final).group(1) + "/"
    eps = [urljoin(root, u) for u in re.findall(r'href="([^"]+)"', html)
           if re.search(r"episodio", u, re.I) and "$" not in u]
    return eps[-1:] if eps else []


def monoschinos_servers(base, ep_url):
    """monoschinos: POST ajax_pagination acc=opt&i=<enc> → data-player b64."""
    html, final = http_get(ep_url)
    m = re.search(r'data-encrypt="([^"]+)"', html)
    if not m:
        return []
    body, _ = http_post(f"{base}/ajax_pagination", f"acc=opt&i={m.group(1)}",
                        referer=final,
                        extra_headers={"Accept": "application/json, text/javascript, */*; q=0.01"})
    out = []
    for b64 in re.findall(r'data-player="([^"]+)"', body):
        try:
            url = base64.b64decode(b64).decode("utf-8", "replace")
            out.append(("", unescape(url)))
        except Exception:
            pass
    return out


def latanime_list(base):
    """latanime: /emision → enlaces de animes."""
    html, final = http_get(f"{base}/emision?p=1")
    root = re.match(r"(https?://[^/]+)", final).group(1)
    links = [urljoin(root + "/", u) for u in re.findall(r'href="([^"]+)"', html)
             if re.search(r"(?:^|/)/?anime/[^/\"#?]+$", u) and "$" not in u]
    seen, uniq = set(), []
    for l in links:
        if l not in seen:
            seen.add(l)
            uniq.append(l)
    return uniq


def latanime_episode(base, anime_url):
    """latanime: div.row > ... > a → enlaces de episodio."""
    html, final = http_get(anime_url)
    root = re.match(r"(https?://[^/]+)", final).group(1) + "/"
    eps = [urljoin(root, u) for u in re.findall(r'href="([^"]+)"', html)
           if re.search(r"capitulo|episodio", u, re.I) and "$" not in u]
    return eps[-1:] if eps else []


def latanime_servers(base, ep_url):
    """latanime: a.play-video[data-player] → base64 → URL embed."""
    html, _ = http_get(ep_url)
    out = []
    for b64 in re.findall(r'data-player="([^"]+)"', html):
        try:
            url = base64.b64decode(b64).decode("utf-8", "replace")
            out.append(("", unescape(url)))
        except Exception:
            pass
    return out


def tvanime_list(base):
    """tvanime (extensión animefenix, baseUrl redirige aquí): home → /anime/<slug>."""
    html, final = http_get(f"{base}/")
    root = re.match(r"(https?://[^/]+)", final).group(1)
    links = [urljoin(root + "/", u) for u in re.findall(r'href="([^"]+)"', html)
             if re.search(r"/anime/[^/\"#?]+$", u) and "$" not in u]
    seen, uniq = set(), []
    for l in links:
        if l not in seen:
            seen.add(l)
            uniq.append(l)
    return uniq


def tvanime_episode(base, anime_url):
    """tvanime: enlaces /anime/<slug>/episodio/episodio-N en la página del anime."""
    html, final = http_get(anime_url)
    root = re.match(r"(https?://[^/]+)", final).group(1)
    eps = [urljoin(root, u) for u in re.findall(r'href="([^"]+)"', html)
           if re.search(r"/episodio/", u) and "$" not in u]
    return eps[-1:] if eps else []


def tvanime_servers(base, ep_url):
    """tvanime: botones data-server-url con data-server-name."""
    html, _ = http_get(ep_url)
    out = []
    for m in re.finditer(r'data-server-url="([^"]+)"[^>]*data-server-name="([^"]*)"', html):
        out.append((m.group(2), m.group(1)))
    return out


PROVIDERS = [
    # nombre, base, fn_list, fn_episode, fn_servers
    ("animeav1", "https://animeav1.com", av1_list, av1_episode, av1_servers),
    ("veranimes", "https://wwv.veranimes.net", veranimes_list,
     veranimes_episode, veranimes_servers),
    ("monoschinos", "https://wwv.monoschinos2.net", monoschinos_list,
     monoschinos_episode, monoschinos_servers,
     {"resolve_base": True}),
    ("latanime", "https://latanime.org", latanime_list, latanime_episode,
     latanime_servers, {"resolve_base": True}),
    ("tvanime", "https://animefenix2.tv", tvanime_list, tvanime_episode,
     tvanime_servers, {"resolve_base": True}),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="matrix.csv")
    ap.add_argument("--animes", type=int, default=2,
                    help="animes a probar por proveedor")
    args = ap.parse_args()

    rows, failures = [], []
    for prov in PROVIDERS:
        name, base, fn_list, fn_ep, fn_servers = prov[:5]
        opts = prov[5] if len(prov) > 5 else {}
        if opts.get("resolve_base"):
            try:
                _, base = http_get(base)
                base = base.rstrip("/")
            except Exception as e:
                failures.append((name, "list", repr(e)))
                print(f"[{name}] LIST FAIL: {e}", file=sys.stderr)
                continue
        try:
            animes = fn_list(base)[: args.animes]
        except Exception as e:
            failures.append((name, "list", repr(e)))
            print(f"[{name}] LIST FAIL: {e}", file=sys.stderr)
            continue
        print(f"[{name}] {len(animes)} animes")
        for anime_url in animes:
            try:
                eps = fn_ep(base, anime_url)
            except Exception as e:
                failures.append((name, "episode", repr(e)))
                continue
            for ep_url in eps[:1]:
                try:
                    servers = fn_servers(base, ep_url)
                except Exception as e:
                    failures.append((name, "servers", repr(e)))
                    continue
                for label, url in servers:
                    rows.append({
                        "provider": name,
                        "anime": anime_url,
                        "episode": ep_url,
                        "server": label,
                        "url": url,
                    })

    with open(args.out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["provider", "anime", "episode", "server", "url"])
        w.writeheader()
        w.writerows(rows)

    # resumen por dominio de host
    from collections import Counter
    domains = Counter()
    for r in rows:
        m = re.match(r"https?://([^/]+)", r["url"])
        if m:
            domains[m.group(1)] += 1
    print(f"\n== {len(rows)} URLs → {args.out} ==")
    for d, c in domains.most_common():
        print(f"  {c:3d}  {d}")
    if failures:
        print("\nFallos:", file=sys.stderr)
        for f_ in failures:
            print("  ", f_, file=sys.stderr)
    return 0 if rows else 1


if __name__ == "__main__":
    sys.exit(main())
