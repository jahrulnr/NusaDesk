# webapp-lab — favicon & cache test fixtures

A single stdlib-only Python server that runs every favicon/cache edge case at
once. A registered web app is always `http://127.0.0.1:<port>/` — the document
root is the only document the favicon fetcher reads — so **one scenario = one
port = one launcher tile**.

## Run

On the workstation (Python 3, no packages needed):

```bash
python3 scripts/webapp-lab/serve.py
for p in $(seq 8401 8416); do adb reverse tcp:$p tcp:$p; done
```

`adb reverse` maps the device's loopback onto this machine, so the app treats
each port exactly like a real in-guest app. The same file can also run inside
the guest when the guest has python3.

Register each port as a web app in the launcher. Every page prints what its
tile **should** show; the server's stdout logs every request
(`[port] GET /path -> status`) so the fetcher's probe, candidate tries, and
`/favicon.ico` fallback are observable separately from the WebView's own loads.

## Scenarios

| Port | Slug | Case | Expected tile |
| --- | --- | --- | --- |
| 8401 | `fallback-ico` | No icon links; `/favicon.ico` = multi-size ICO (PNG frames) | Icon via auto-fallback + ICO unwrap |
| 8402 | `fallback-ico-dib` | `/favicon.ico` = DIB-in-ICO with AND mask | Icon, top half transparent |
| 8403 | `no-icon` | No links, no `/favicon.ico` | Monogram, no error |
| 8404 | `multi-size` | 5 declared links: 404, red-32, green-64, blue-128, SVG | Red-32 — first decodable within the 3-candidate cap |
| 8405 | `fmt-png` | Declared `icon.png` | Teal icon |
| 8406 | `fmt-jpg` | Declared `icon.jpg` | Orange icon |
| 8407 | `fmt-webp` | Declared `icon.webp` | Teal-circle icon |
| 8408 | `fmt-gif` | Declared `icon.gif` | Monogram — no platform GIF codec (verified API 31); falls through to favicon.ico, which 404s |
| 8409 | `fmt-bmp` | Declared `icon.bmp` | Blue icon |
| 8410 | `fmt-ico-link` | Declared `icon.ico` | Icon via link-declared ICO |
| 8411 | `svg-then-fallback` | Declared SVG (skipped); `/favicon.ico` serves PNG bytes | Orange icon |
| 8412 | `cross-port` | Declared icon on `127.0.0.1:8405` (different origin) | Monogram — rejected |
| 8413 | `apple-touch` | Only `apple-touch-icon` declared | Red icon |
| 8414 | `mislabeled` | `icon.png` serving ICO container bytes | Icon — bytes win over extension |
| 8415 | `cache-lab` | `no-store` page + `max-age` badge that prints its own fetch version | Reload → badge stale; **Reload without cache** → badge resyncs |
| 8416 | `kbd-lab` | A plain text input for the keyboard-settlement check (ADR-0065) | Keyboard up in-page; Home/Back lands on the launcher with it closed (`dumpsys input_method` → `mInputShown=false`) |

## The cache lab (8415)

The page re-renders on every load (`Cache-Control: no-store`) and tells you the
render number plus the badge's expected digit/colour. `badge.png` is cached for
a day, so a plain reload reuses it while the page advances — the mismatch is
the stale cache made visible. `Reload without cache` (ADR-0064) forces the
badge to be refetched and the two resync. `Clear cache` followed by a reload
does the same. The page also fetches `/api/state` so the server's own
`badge_fetches` counter proves whether a refetch happened.

## Coverage notes

- `multi-size` exercises the `FaviconLinkParser.MAX_CANDIDATES` cap: five
  declarations exist, only the first three are tried, and the first decodable
  one wins — the 404 declaration proves candidates are tried in order.
- `fallback-ico-dib` covers the DIB→BMP rewrite *and* the AND mask (the top
  half must show the tile plate through, not opaque).
- `cross-port` proves a different loopback port is a different origin: the
  fetcher never follows the declaration.
- User-picked icon override (picker file beats favicon, picked `.ico` decodes
  through `IconBitmapDecoder.decodePicked`) still needs a manual pick — it is
  not servable from a fixture port.
