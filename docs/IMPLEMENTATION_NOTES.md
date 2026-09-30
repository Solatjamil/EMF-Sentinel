# EMF Sentinel — Implementation Notes (Monetization + Real-Sensor Rework)

Date: 2026-09-12 · app: `com.goshbuzz.emfsentinel` (EMF Sentinel, GoshBuzz)

**Updated 2026-09-30 — Play Console recommendations for release `3.3 (6)` + AdMob native ad:
see [§F](#f-play-console-recommendations-for-release-33-6--admob-native-ad).** Those changes
were written and statically reviewed but **not compiled** (see the verification log).

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
| `androidx.fragment:fragment` | **1.8.9** (explicit pin) | Google Maven POMs, 30 Sep 2026 — overrides the transitive 1.1.0 Play flags; rationale in §F.1 |

Requirements check: GMA 25.x needs compileSdk ≥ 35 ✅ (36), minSdk ≥ 23 ✅ (24).
Google Play's target-API release requirement is separate — targetSdk 36 ✅.

**Centralized AdMob IDs per build variant** (`app/build.gradle.kts`):

| Variant | App ID | Banner unit | Native unit | Purpose |
|---|---|---|---|---|
| `debug` | `ca-app-pub-3940256099942544~3347511713` (Google sample) | `ca-app-pub-3940256099942544/9214589741` (Google TEST adaptive banner) | `ca-app-pub-3940256099942544/2247696110` (Google demo **Native**) | Development — never live ads |
| `qa` | your production App ID `ca-app-pub-4067724379997931~6208950543` | Google TEST banner | Google demo Native | Validates **your real AdMob Privacy & messaging config** without serving live ads |
| `release` | your production App ID | **your production banner `ca-app-pub-4067724379997931/4082502150`** | **your production native unit `ca-app-pub-4067724379997931/9411043977`** | Store builds |

Release IDs default to the values from your brief and are overridable with Gradle
properties:
`./gradlew :app:assembleRelease -PADMOB_APP_ID=... -PADMOB_BANNER_UNIT_ID=... -PADMOB_NATIVE_UNIT_ID=...`
**Confirm all three production values against the AdMob console before publishing.**
The demo unit IDs come from <https://developers.google.com/admob/android/test-ads> (checked 30 Sep 2026).
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
- `ads/NativeAdCard.kt` — **AdMob Native (advanced)** ad shown **alongside** the banner (the
  banner is unchanged). A real `NativeAdView` with every asset registered before
  `setNativeAd()` (headline, body, CTA, icon, advertiser, stars, `MediaView`), a visible
  **“Ad” badge**, space for the **AdChoices** overlay, `MediaView` ≥ 120 dp / 16:9 / never cropped,
  video starts muted, no custom click handlers and no clickable background. Same consent
  gate as the banner; one ad per composition, destroyed on dispose / consent withdrawal /
  late arrival; failure logs only (logcat tag `NativeAdCard`) and the card then occupies
  **zero space**. Shown in **Insights** and **Health**, each between two read-only cards
  (never next to switches, the radar canvas or the tab bar, to avoid accidental clicks).
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
8. **Native ad** (debug/qa build → Google demo native unit):
   - **Insights** and **Health**: once loaded, a bordered card appears between the two read-only
     cards with a yellow **“Ad”** badge (top-left), the **AdChoices** icon (top-right, not
     covered), app icon + headline, media (image or *muted* video, never cropped), body text and a
     call-to-action button. The demo creative carries Google's “Test Ad” label.
   - Tap the CTA / media → the test landing page opens (the SDK handles clicks; the app has no
     click handler). Tapping empty card background does nothing.
   - Airplane mode → the card never appears, **no empty gap** is left in the screen and nothing
     crashes; logcat (`NativeAdCard`) shows one `Native ad failed` line per visit, no retry loop.
   - Consent denied / withdrawn → no native request is made (and a visible card disappears).
   - Toggle dark/light, switch tabs quickly, rotate, background/foreground the app: no crash,
     card colours follow the theme, the banner keeps working underneath.
9. **Edge-to-edge** — test on an **Android 15/16** device/emulator *and* on an Android 10–14 one:
   - Gesture navigation **and** 3-button navigation; portrait **and** landscape; a device or
     emulator with a **display cutout**.
   - Nothing interactive sits under the status bar, cutout or navigation bar; header, tab bar
     and banner respect the side insets in landscape; the splash watermark clears the nav bar.
   - Status-bar and navigation-bar **icons stay legible** after toggling the in-app dark/light
     switch (that toggle is independent of the system theme).
10. **Release build smoke test** (R8 is enabled for the first time in `3.4 (7)`): install the signed
    release build and walk every tab — Scanner, Heatmap, AR (camera permission + preview), Insights,
    Health, Settings, Floor-Plan Mapper (draw/save/reload), LAN scan, consent form. Any crash that
    does not happen in debug points at a missing R8 keep rule (see `app/proguard-rules.pro`).

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
- [ ] AdMob: native unit `…/9411043977` belongs to this app and is format **Native advanced**.
      A brand-new unit can take **up to ~1 hour** before it serves; until then requests return
      “no fill” (the card simply stays hidden). Test only with debug/qa builds or a registered
      test device — never click live ads.
- [ ] Upload **`3.4 (7)`** (Play already has `3.3 (6)`; the repo previously said `3 / 3.0`).
      Bump `versionCode`/`versionName` in `app/build.gradle.kts` for every further upload.
- [ ] After upload, open each of the three recommendations (Release dashboard → *Recommendations*)
      and compare with §F: the **“These start in the following places”** list for the deprecated-API
      item should no longer name `MainActivity` / `androidx.activity.EdgeToEdgeApi*`. If it only
      names `com.google.android.gms.ads.*` it is the ads SDK (see §F.3) — not fixable in this repo.

---

## F. Play Console recommendations for release `3.3 (6)` + AdMob native ad

Play Console flagged three items on `3.3 (6)`. Findings, fixes and how to verify each:

### F.1 “Outdated SDK” — `androidx.fragment:fragment` 1.1.0 (Play asks for 1.2.1+)

- **Cause** (read from the Google Maven POMs): the app has no Fragment code. `play-services-basement:18.9.0`
  (a compile dependency of `play-services-ads`, `play-services-ads-api` **and** UMP) and
  `androidx.camera:camera-view:1.5.0` (runtime dependency) both declare `fragment:1.1.0`, and
  nothing else in the graph raises it.
- **Fix:** explicit `implementation(libs.androidx.fragment)` = **1.8.9** (`gradle/libs.versions.toml`).
  1.8.9 is the newest release whose own requirements (activity 1.8.1, lifecycle 2.6.1, core-ktx
  1.2.0) are all already exceeded here, so it changes *only* fragment. The latest stable, 1.9.1,
  would additionally raise lifecycle to 2.10.0 (+ tracing 2.0.0) in an app pinned to Compose BOM
  2024.09 — a larger, untested jump for no benefit to this warning.
- **Verify:** `./gradlew :app:dependencyInsight --dependency androidx.fragment --configuration releaseRuntimeClasspath`
  must show `androidx.fragment:fragment:1.8.9`.

### F.2 “Edge-to-edge may not display for all users”

With `targetSdk 36` the app is edge-to-edge on Android 15+ whether it opts in or not, so every
screen has to handle insets. Gaps found and closed (`MainActivity.kt`, `ui/EdgeToEdge.kt`,
`res/values/themes.xml`):

- Edge-to-edge was enabled *after* `super.onCreate()` → now before it, via `enableEdgeToEdgeCompat()`.
- The default `enableEdgeToEdge()` picks status/navigation **icon** colours from the *system* theme,
  but this app's dark/light switch is independent → `SystemBarIconsEffect` follows the in-app theme.
- Only the top (header) and bottom (spacer) insets were applied; left/right insets and the **display
  cutout** were ignored (landscape notch / side navigation bar could cover the logo, buttons, tab bar
  and banner) → header, bottom bar and Scaffold body now use status + navigation bars + cutout.
- The splash watermark was a fixed 32 dp from the bottom → hidden behind a 3-button bar; now inset-aware.
- Transparent bars for Android 7–14 come from the theme; below API 26 (no dark nav-bar icons) the light
  theme paints a dark strip behind the navigation bar so the buttons stay visible.
- **Not changed:** dialogs keep Compose's default (`decorFitsSystemWindows = true`, content stays inside the
  system bars); the AR camera preview stays inside the Scaffold padding.
- **Caveat:** Play's detector for this item is opaque. The previous build *already called*
  `enableEdgeToEdge()` and was still flagged (as are many apps that do), so this list fixes every real inset
  problem found by reading the code, but only an upload shows whether Play's heuristic clears. Screens
  owned by libraries (AdMob's full-screen `AdActivity`, the UMP consent form) are outside this repo's control.

### F.3 “Uses deprecated APIs or parameters for edge-to-edge”

Android 15 deprecated (per <https://developer.android.com/about/versions/15/behavior-changes-15>)
`Window.setStatusBarColor / getStatusBarColor`, `setNavigationBarColor / getNavigationBarColor`,
`setNavigationBarDividerColor`, the matching theme attributes, and the cutout modes `SHORT_EDGES` /
`DEFAULT`. The Play Console text you received did not include the “starts in these places” list, so
the exact callers could not be read — but the evidence is consistent:

- This repo's **own code and resources contain none of these** (grepped, including `res/` and tests).
- The well-documented source (public reports from Sep 2026 verified with `dexdump`) is
  **`androidx.activity`'s `enableEdgeToEdge()`**: its internal `EdgeToEdgeApi23…Api35` shims call the
  deprecated setters and write `SHORT_EDGES`. Play's static scanner flags them even though the app
  never calls them itself, and upgrading `androidx.activity` does not remove them.
  `WindowCompat.enableEdgeToEdge(Window)` (androidx.core — the replacement that the `androidx.activity`
  1.14 alphas now point `enableEdgeToEdge()` to) is **not** a way out either: its source calls the same
  `setStatusBarColor/setNavigationBarColor` and `SHORT_EDGES`. Only *not referencing* them (plus R8
  dropping the unused library copies) removes them from the DEX.
- **Fix, two parts:**
  1. `enableEdgeToEdge()` is replaced by `enableEdgeToEdgeCompat()` (`ui/EdgeToEdge.kt`), which uses only
     non-deprecated calls: `WindowCompat.setDecorFitsSystemWindows(false)`, contrast enforcement off
     for the navigation bar (API 29+) and `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` (API 30+). Transparent
     bars come from `themes.xml`.
  2. **R8 is now enabled for release** (`isMinifyEnabled` + `isShrinkResources`). Before, the
     app shipped *every* library class, so the now-unused `EdgeToEdgeApi*` shims would still have been
     in the DEX (and flagged). R8 strips them.
- **Verify** after `./gradlew :app:bundleRelease`: R8's `app/build/outputs/mapping/release/usage.txt`
  should list `androidx.activity.EdgeToEdgeApi…` (= removed) and `mapping.txt` should not contain them.
- **Judgement call — theme attributes:** `android:statusBarColor` / `android:navigationBarColor` in
  `themes.xml` are *themselves* on Android 15's deprecated list (ignored there). They are kept because the
  alternative is a code call that Play definitely flags, and every Play report examined so far lists code
  references only. If a future report names them as “parameters”, delete those two `<item>` lines: Android
  7–14 then shows the platform's default opaque bars (Android 15+ is unaffected — it ignores them anyway).
- **What may remain (outside this repo):** a public report on `play-services-ads` 25.4.0
  (`cph5236/HeapGame#167`) traced a leftover `SHORT_EDGES` flag to the ads SDK's own overlay code /
  its `hsdp` dependency. If Play's new list only names `com.google.android.gms.ads.*` (or
  `com.google.android.play…`), that is the Google ads SDK: nothing in this app can remove it, and it
  is not a reason to hold the release. `play-services-ads` 25.5.0 (17 Sep 2026) has an identical
  dependency set and its notes mention no edge-to-edge change, so the SDK was deliberately **not**
  bumped in this change.

### F.4 AdMob Native ad — policy mapping

Follows <https://developers.google.com/admob/android/native/advanced> (Legacy GMA SDK — the two SDK
families are never mixed) and the native-advanced policy <https://support.google.com/admob/answer/6329638>:

| Requirement | How `ads/NativeAdCard.kt` meets it |
|---|---|
| Ad attribution badge (“Ad”, ≥ 15 px) | Always-visible amber “Ad” badge, ≥ 18 dp tall, drawn by the app |
| AdChoices overlay visible | SDK places it top-right (`ADCHOICES_TOP_RIGHT`); 28 dp reserved, never overlapped |
| All assets inside the `NativeAdView`, all registered | headline, body, CTA, icon, advertiser, stars, `MediaView` registered before `setNativeAd()` |
| Video `MediaView` ≥ 120 × 120 dp; no stretching/cropping | min 120 dp, fixed 16:9, `FIT_CENTER`, landscape media requested, video starts muted |
| Required/recommended fields shown | badge, headline, media, icon (if given), CTA, body, stars, advertiser |
| No truncation below 25 / 90 / 15 characters | headline 2 lines, body 3 lines, full-width one-line CTA |
| No custom click handlers; no clickable white space | none registered; card background is not clickable |
| Distinct from content; nothing overlaps the ad | bordered card + badge; no overlays |
| Sufficient text contrast | palettes chosen ≥ 4.5:1 in both themes |
| Not near touch targets | only between read-only cards (Insights, Health) |
| Consent (UMP) | same `canRequestAds` gate as the banner; nothing requested otherwise |
| Destroy ads | `NativeAd.destroy()` on dispose, on consent withdrawal and for late arrivals |
| Test ads in development | debug/qa use Google's demo native unit; only `release` has the live unit |

The banner (`AnchoredAdaptiveBanner`) is untouched and keeps its own slot under the tab bar.

### F.5 Release numbering

Play holds `3.3 (6)`; the repo said `3 / 3.0`, so an upload built from it would be rejected as a lower
version code. `app/build.gradle.kts` is now **`versionCode 7` / `versionName "3.4"`**.

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

### 2026-09-30 — Play recommendations + native ad (§F) — NOT COMPILED

This session's authoring environment had **no JDK, Gradle, Android SDK or network access for shell
commands**, so **none of the §F changes have been built, run or tested**. What was checked instead:

| Check | Result |
|---|---|
| Dependency graph from the Google Maven POMs (`play-services-ads` 25.4.0/25.5.0, `ads-api`, `basement` 18.9.0, `ump` 4.0.0, `camera-view` 1.5.0, `fragment` 1.8.9/1.9.1, `compose-bom` 2024.09.00) | ✅ fragment 1.1.0 traced to `basement` + `camera-view`; 1.8.9 needs nothing newer than is already resolved |
| API names vs. the official references (`NativeAdView`, `NativeAd`, `NativeAdOptions`, `WindowInsetsSides`) and the `androidx.core` `WindowCompat` source | ✅ — and one trap avoided: `WindowInsetsSides.plus` is public only from Compose foundation 1.10 (this project resolves 1.7.0), so it is not used |
| Bracket/structure check of every touched Kotlin file (script) | ✅ balanced |
| New plain-JVM test `AdUnitConfigTest` (debug/qa must carry Google demo banner + native units; release IDs well-formed) | ⚪ written, **NOT RUN** |
| `:app:compileDebugKotlin`, `assembleDebug/Qa/Release`, `testDebugUnitTest`, lint | ⚪ **NOT RUN** |
| On-device: ads render, insets on Android 10–16, R8 release run | ⚪ **NOT RUN** |
| Play Console: which warnings clear | ⚪ only visible after uploading `3.4 (7)` |

Run on a workstation **before uploading**:

```bash
./gradlew :app:assembleDebug :app:assembleQa :app:testDebugUnitTest
./gradlew :app:dependencyInsight --dependency androidx.fragment --configuration releaseRuntimeClasspath   # expect 1.8.9
./gradlew :app:bundleRelease     # needs KEYSTORE_PATH / STORE_PASSWORD / KEY_PASSWORD; first R8 build of this app
grep -c "EdgeToEdgeApi" app/build/outputs/mapping/release/usage.txt   # > 0  => shims were removed by R8
```
Then run QA checklist items 8–10 on hardware.
