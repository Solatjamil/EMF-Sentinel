# EMF Sentinel

Precision RF & bio-telemetry dashboard for Android — real magnetometer EMF metering,
per-story architect floor-plan mapping, live LAN device discovery, defensive RF
interference (anti-jam) alerts, IR lens-finder sweep, and consent-gated AdMob ads
(anchored adaptive banner + native ad).

**No AI, no API keys, fully on-device.** The Google AI Studio scaffolding (Gemini key,
secrets plugin, Firebase BOM) and the unused Room/Retrofit/OkHttp/Moshi template
dependencies were removed after verifying zero usage in the code. The app needs no
accounts or secrets to run.

## Build

```bash
# JDK 21 + Android SDK 36 required; sdk.dir goes in local.properties
./gradlew :app:assembleDebug      # Google TEST ads only
./gradlew :app:assembleQa         # your AdMob App ID + TEST banner/native units (UMP config check)
./gradlew :app:assembleRelease    # production IDs — confirm in AdMob console first
```

Release builds are minified with **R8**. Always install and launch the signed release build before
uploading it — debug builds do not run R8, so only a release run shows a missing keep rule
(see `docs/IMPLEMENTATION_NOTES.md` §F.6 for the launch crash this already caught once).

A ready debug APK (test ads) from the latest build is in
[`artifacts/EMF-Sentinel-v3.0-debug-testads.apk`](artifacts/EMF-Sentinel-v3.0-debug-testads.apk).

## Read this first

**`docs/IMPLEMENTATION_NOTES.md`** — full rundown: the monetization integration
(consent, per-variant ad IDs, QA/test strategy), the real-sensor rework (what is
measured vs. estimated vs. explicitly simulated), the urgent keystore-rotation warning,
and the device-QA + store-release checklists.
