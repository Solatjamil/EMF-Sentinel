# EMF Sentinel — Implementation Notes (Monetization + Real-Sensor Rework)

Date: 2026-09-12 · app: `com.goshbuzz.emfsentinel` (EMF Sentinel, GoshBuzz)

---

## 🚨 0. URGENT SECURITY ISSUE — ACT NOW

`my-upload-key.jks` and `upload_certificate.pem` are committed to the git repository,
and the repository is **public**. Anyone can clone your upload key. Keystore passwords
being in env vars does not save you — keys must never be in git history at all.

1. Play Console → Setup → App integrity → **Request upload key reset** (Google Play
   App Signing makes this safe — your app keeps working; only your local upload key rotates).
2. Remove the files and purge history: `git rm my-upload-key.jks upload_certificate.pem`,
   then use `git filter-repo` / BFG to strip them from all history, then force-push.
3. Store keystores OUTSIDE the repo (e.g. a secrets vault / CI secret). `.gitignore`
   now blocks `*.jks`/`*.keystore` going forward.

---

## A. Project & SDK assessment, chosen approach, pinned versions

**Stack:** Native Android, Kotlin + Jetpack Compose (Google AI Studio template, single
`MainActivity.kt` + `ui/theme/*`). `applicationId = com.goshbuzz.emfsentinel`,
`namespace = com.example`, minSdk **24**, targetSdk **36**, compileSdk **36**.

**Ads SDK path chosen: Google Mobile Ads SDK (“Legacy/GMA”) — retained and upgraded.**
The project already integrated `play-services-ads` (Legacy) end-to-end (dependency +
manifest App ID + activity `AdView`). The brief's baseline recommends Next-Gen for *new*
integrations but explicitly says not to force Next-Gen under an existing maintained
integration, and never to mix the two SDKs. For this single anchored-banner app, staying
on the maintained Legacy path is the correct, lowest-risk choice.

| Component | Version | Verification |
|---|---|---|
| `com.google.android.gms:play-services-ads` | **25.4.0** | Checked Google's Maven listing today (12 Sep 2026) — latest listed release |
| `com.google.android.ump:user-messaging-platform` | **4.0.0** | Checked Google's Maven listing today (12 Sep 2026) — latest listed release |

Requirements check: GMA 25.x needs compileSdk ≥ 35 ✅ (36), minSdk ≥ 23 ✅ (24).
Google Play's target-API release requirement is separate — targetSdk 36 ✅.

**Centralized AdMob IDs per build variant** (`app/build.gradle.kts`):

| Variant | App ID | Banner unit | Purpose |
|---|---|---|---|
| `debug` | `ca-app-pub-3940256099942544~3347511713` (Google sample) | `ca-app-pub-3940256099942544/9214589741` (Google TEST adaptive banner) | Development — never live ads |
| `qa` | your production App ID `ca-app-pub-4067724379997931~6208950543` | Google TEST banner | Validates **your real AdMob Privacy & messaging config** without serving live ads |
| `release` | your production App ID | **your production banner `ca-app-pub-4067724379997931/4082502150`** | Store builds |

Release IDs default to the values from your brief and are overridable with Gradle
properties: `./gradlew :app:assembleRelease -PADMOB_APP_ID=... -PADMOB_BANNER_UNIT_ID=...`
**Confirm both production values against the AdMob console before publishing.**
Note: the app's old hard-coded unit `…/9096937952` was replaced by `…/4082502150` per the
brief — if both exist in your console, keep one and delete/blocklist the other to avoid
confusion.

**What was removed (was live in v3.0 on the store — policy risks):**
- A **fabricated ad UI**: fake "Google Play Store / VPN" campaigns with invented star
  ratings, a fake "Ad" badge, fake AdChoices triangle, and links to the Play Store.
  Fabricating ad creatives/controls is an AdMob policy violation.
- A **12-second manual ad refresh loop** — competes with the SDK's refresh; automatic
  refresh belongs in the AdMob console per ad unit.
- Hard-coded `AdSize.BANNER` 320×50 in a fixed 56 dp frame + ad unit hard-coded in
  composable default params.
- `MobileAds.initialize()` running unconditionally in `onCreate` with no consent gate.

**New architecture:**
- `ads/AdConsentManager.kt` — UMP 4.0 consent gate: `requestConsentInfoUpdate()` at every
  launch **and** every resume; `loadAndShowConsentFormIfRequired()`; ads initialize/load
  **only** when `canRequestAds()` is true; previous-session consent honored (state re-read
  after success, error, and form callbacks); privacy-options requirement tracked.
  Audience flags: general-audience utility app, `setTagForUnderAgeOfConsent(false)`.
  If the store listing's target audience includes children, that flag and the Play
  Families/ads policies must change together.
- `ads/MobileAdsController.kt` — once-per-process, concurrency-safe GMA init on a
  background thread, after consent. No interstitial/rewarded/app-open formats exist.
- `ads/AnchoredAdaptiveBanner.kt` — Compose banner: measures the real container width
  (never 360 dp hard-coded), uses `getCurrentOrientationAnchoredAdaptiveBannerAdSize`,
  reserves a **fixed-height slot** below the bottom navigation (no layout jumps on
  load/fail/consent-change), one load per slot (no request loops), `destroy()` on
  dispose, failure never crashes/blocks UI, zero overlay on the creative.
- **Settings → “Privacy Options”** row appears whenever UMP reports
  `PrivacyOptionsRequirementStatus.REQUIRED` and opens `showPrivacyOptionsForm()`.

---

## B. Feature rework — fabricated data replaced with REAL sensors

| Before (v3.0) | Now |
|---|---|
| EMF reading = `Math.random()` around 42.8 µT | **Real magnetometer** total-field µT, low-pass filtered (`sensors/EmfSensorManager.kt`). Devices without a magnetometer show a visible **“SIMULATED SENSOR”** badge — never silent fakery. |
| “BIO-SYNC: ACTIVE” over 5 hard-coded fake subjects (person, pet, 3 devices) and 7 hard-coded walls | Empty-until-real. Subjects now come from: saved per-story blueprint, live Wi-Fi scans, LAN discovery. Indicator reads **BIO-SYNC: LIVE SENSORS** (or SIMULATED SENSORS). |
| “REFLECTIONS SCAN” button generated **random walls** | Removed. Replaced by the **Architect Floor-Plan Mapper** (below). |
| Fake Wi-Fi RSSI jitter | Real `WifiManager` RSSI of the connected network. |
| Hidden-camera hunt: none | Real heuristic aids (below). |

### B.1 Architect Floor-Plan Mapper (`floorplan/FloorPlanMapper.kt`, `FloorPlan.kt`)
**Honesty constraint:** a consumer phone **cannot see through walls with Wi-Fi** —
through-wall imaging needs research-grade multi-radio hardware (CSI), not Android APIs.
What ships instead is the real, honest capability:

- Per-building-story blueprints: Basement / Ground / Floor 1..N, each saved on-device
  (SharedPreferences JSON via `FloorPlanStore`).
- Architect-style canvas: 24×24 graph grid, **calibrated scale** (0.25–5 m per cell),
  drag-to-draw walls with grid + 15° angle snapping, double-line wall rendering with
  **metric dimension labels**, door openings, named room labels.
- **Router anchor** placed by the user; every device marker is linked to it with the
  dotted RF-link lines you asked for.
- **Devices:** real LAN scan (Kernel ARP table + bounded ICMP sweep of the /24, like
  Fing) yields IP/MAC/**OUI vendor** (incl. Hikvision/Dahua/Wyze/Ring/Espressif camera
  chipsets flagged ⚠). Tapping places each discovered device; **dragging moves it into
  the exact room** — real “exact placement” is a calibration act, which the UI states.
- Applying a plan renders the same walls + devices + dotted links onto the Bio-Sync
  radar; floor label shown on radar.
- Nearby Wi-Fi APs are auto-plotted on the radar with **FSPL distance estimation from
  real RSSI + frequency** (angles are heuristic on a single phone — labeled as such).

### B.2 Real-time EMF & hidden-camera scanning
- **Live µT meter** (real sensor), session min/avg/max graph now reflects hardware.
- **Health → “Hidden-Electronics EMF Sweep”:** baseline vs. live spike detection with a
  large-print warning; instructions to sweep walls/clocks/detectors/chargers. Explicitly
  labeled heuristic — no app can *guarantee* camera detection from a magnetometer.
- **AR tab → “IR Lens Finder”:** real technique — red-filter camera view **+ torch**;
  night-vision IR LEDs of concealed cameras show as bright on-screen dots invisible to
  the eye. Labeled as an aid requiring manual verification.
- LAN vendor lookup flags camera-chipset OUIs (Hikvision, Dahua, Wyze, Ring, Espressif).

### B.3 Signal jammer request — NOT implemented (and never will be)
Requested: a button to “jam the whole network / 30-ft radius”. This is **illegal**
(PTA in Pakistan; FCC/Ofcom elsewhere — deliberate interference with radio comms) and
**physically impossible** on consumer Wi-Fi chipsets (no transmit-interference API exists;
apps claiming it are fake). Instead a **legal defensive feature** ships:
`net/JammingDetector.kt` passively watches all visible APs + the connected link and
raises **“RF INTERFERENCE ALERT — possible jamming nearby”** when a synchronized
across-the-board collapse is detected (the actual signature of someone jamming you).

### B.4 Other correctness fixes
- Version text hard-coded `v2.4` while shipping `3.0` → now `v${BuildConfig.VERSION_NAME}`.
- Duplicate `LocalContext` import removed; ads imports moved into `ads/*`.
- Fake “TRACKING N SUBJECTS” now counts real subjects only.

### B.5 AI/Firebase removal (user request — the app uses ZERO AI)
No Gemini/Firebase call existed anywhere in the code; all AI scaffolding has been excised:
- Deleted: `.env.example` (held the `GEMINI_API_KEY` placeholder), `metadata.json`
  (AI Studio `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API` marker), `assets/.aistudio/`,
  and the manifest's leftover firebase analytics meta-data.
- Build config: removed the **secrets-gradle-plugin** (its only job was injecting the
  key), **KSP plugin**, **Firebase BOM**, and the dead template dependencies
  Room, Retrofit, OkHttp, Moshi — none were referenced by a single line of code.
  Result: identical functionality, smaller APK, faster builds, **zero API keys**.
- The app needs NO keys or accounts to run — only the AdMob IDs which are config,
  not user secrets.

---

## C. Build / run instructions + verification status

```bash
# Prereqs: JDK 17, Android SDK platform-36 + build-tools 36, sdk.dir in local.properties
./gradlew :app:assembleDebug      # TEST ads (Google sample IDs)
./gradlew :app:assembleQa         # your App ID + TEST banner (validates UMP config)
./gradlew :app:assembleRelease    # production IDs — confirm in AdMob console first
```

**Verified in this workspace:** see “Build verification log” at the bottom of this file.
**Not verified here (needs a device/emulator):** actual ad rendering, UMP form display,
sensor reads, LAN sweep reachability, camera torch.

---

## D. Device QA checklist (run on a phone)

1. Debug install shows Google **“Test Ad”** label in the bottom slot; slot height is
   stable before/after load; bottom nav never jumps; rotate → banner resizes (adaptive).
2. With internet off → app works, slot stays empty, no errors/crashes, no retry storm
   (logcat `AnchoredAdaptiveBanner` shows one failure entry only).
3. Consent (QA build + registered test device / UMP debug geography EEA):
   - Fresh install → consent form appears once; deny → no ad request sent.
   - Kill + relaunch → previous-session consent honored; no duplicate prompts.
   - Settings → **Privacy Options** visible (REQUIRED regions) → withdraw consent →
     ads stop loading on next request; resume re-evaluates.
4. Production build on a registered test device only — **never click live ads**.
5. Floor mapper: draw walls (snaps), calibrate m/cell against a measured wall, pin rooms,
   anchor router, LAN scan → devices appear; drag into rooms; Apply → radar shows walls
   + dotted router links; switch floors → independent plans persist after restart.
6. EMF: reading changes near magnets/electronics; no-magnetometer devices show the
   SIMULATED badge. IR finder: torch on, red overlay, IR remote-control LED visible as
   bright dot (quick sanity test of the concept).
7. Jam detector: enable a 2.4 GHz congestor (or toggle router radios) → alert card.

---

## E. Manual release checklist (account/store tasks — code can't do these)

- [ ] **Rotate upload key** (Section 0) — do this first.
- [ ] Confirm App ID `…~6208950543` + banner unit `…/4082502150` in AdMob → this app.
- [ ] AdMob: ad unit = **Banner**, format refresh = automatic (choose in console);
      app verification + “app readiness” review status; Policy center clean.
      (Ad unit creation ≠ approval; reviews commonly take days, sometimes weeks.)
- [ ] **app-ads.txt**: `app-ads.txt` in the repo root is NOT sufficient — host the exact
      personalized snippet from AdMob at `https://<your-dev-site>/app-ads.txt` (the
      developer site linked on your Play listing), then verify it in AdMob.
      Current file content: `google.com, pub-4067724379997931, DIRECT, f08c47fec0942fa0`.
- [ ] AdMob → Privacy & messaging: publish the GDPR/UMP message + truthful privacy
      policy URL. Test with the `qa` build variant.
- [ ] Play Console: **Contains ads = Yes**, Data safety reflects Advertising ID +
      approximate location (Wi-Fi scans) + camera use; Advertising ID declaration;
      target-audience (general vs. Families) consistent with
      `setTagForUnderAgeOfConsent(false)`.
- [ ] Test ad shown ≠ live fill guaranteed: new units/apps need activation time;
      monitor AdMob match rate after release.

---

## Build verification log

Executed in this workspace (2 GB RAM / 2 CPU sandbox, JDK 17.0.20, Gradle 9.7.1,
AGP 9.1.1, offline cached deps):

| Check | Result |
|---|---|
| `:app:compileDebugKotlin` (all Kotlin incl. new modules) | ✅ **BUILD SUCCESSFUL** |
| `:app:assembleDebug` | ✅ **BUILD SUCCESSFUL** → `artifacts/EMF-Sentinel-v3.0-debug-testads.apk` (**16.5 MB after AI-cleanup** — was 19.2 MB; Google test ads). Post-cleanup rebuild verified green |
| Merged manifest (debug) | ✅ `package=com.goshbuzz.emfsentinel`, versionCode 3 / 3.0, AdMob App ID = Google TEST `ca-app-pub-3940256099942544~3347511713` (placeholder injection works) |
| `:app:assembleQa` | ✅ **BUILD SUCCESSFUL** → `artifacts/EMF-Sentinel-v3.0-qa-testads.apk` (**15.8 MB after AI-cleanup** — was 18.5 MB). Merged manifest re-verified post-cleanup: production App ID `ca-app-pub-4067724379997931~6208950543`, banner = Google TEST unit (validates your real UMP config, zero live impressions) |
| `:app:compileDebugUnitTestKotlin` | ✅ **BUILD SUCCESSFUL** — existing test sources compile against the reworked code |
| Unit/Robolectric/screenshot test execution | ⚪ NOT RUN — Robolectric runtime jars + baselines need a full workstation run (`./gradlew testDebugUnitTest`) |
| `release` variant | ⏸ not built here — needs your `STORE_PASSWORD`/`KEY_PASSWORD` env; ID defaults + `-P` overrides are in place |
| API surface used (UMP 4.0.0, Ads 25.4.0) | ✅ verified by disassembling the resolved AARs (`UserMessagingPlatform.loadAndShowConsentFormIfRequired`, `showPrivacyOptionsForm`, `AdSize.getHeight()`…); the two API mismatches this exposed were fixed |
| Lint/device behavior | ⚪ NOT RUN — no emulator/device in this environment. Run checklist D on hardware |

Environment notes: the template's `-Xmx4g` daemon default OOM-killed builds on a 2 GB
box; `gradle.properties` now caps at `-Xmx1280m` and debug skips PNG crunch (matching
the existing release setting). Full cold-cache QA build ≈ 11 min here; warm builds ≈ 40 s. Post-cleanup warm re-verification: debug 53 s, QA 35 s — all green, APKs ~2.7 MB smaller each. Additionally verified end-to-end: `:app:assembleRelease` signed via env-var keystore (scratch key, 12.4 MB) — release merged manifest carries production App ID; aapt badging: com.goshbuzz.emfsentinel v3.0(3), minSdk 24 / targetSdk 36; `:app:testDebugUnitTest` executes green (JUnit4: 1 test, 0 failures). (Robolectric/roborazzi runtime classes still to run on your workstation.)
