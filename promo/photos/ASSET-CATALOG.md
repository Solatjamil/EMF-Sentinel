# Promo asset catalog

## Included

| Promo filename | Repository source | Notes |
|---|---|---|
| `emf-sentinel-logo.jpg` | `app/src/main/res/drawable/img_emf_logo_icon_1782150814680.jpg` | Original 1024 × 1024 project branding image; copied unchanged. It is the one decodable product image in this checkout and is reused with different crops/positions in the film. |

The app's XML launcher foreground points back to that same logo, so it does not add a separate visual asset. The promo uses source-informed motion graphics for the sensor, floor-plan, and network views; these are explicitly presented as visualizations, not captured app screenshots.

## Source assets reviewed but not embedded

- `app/src/main/res/mipmap-*/ic_launcher*.webp` — resolution-specific launcher variants all depict the same logo, but the checked-out files fail image decoding (`identify` reports corrupt WebP). The valid original logo above is used instead of embedding broken files.
- `app/src/test/screenshots/greeting.png` — fails PNG decoding in this checkout and is a Robolectric `Greeting("Robolectric")` test fixture, not an app screen.

No photographic product screenshots or user/download statistics are present in the repository, so the promo does not invent any. Its headline metric is the documented 24 × 24 mapper grid.
