# AirWave — private offline chat over Bluetooth Low Energy

Chat with nearby people over Bluetooth. No internet, no servers, no accounts, no cloud.

## Build

1. Open this folder in **Android Studio** (Hedgehog or newer).
2. Let Gradle sync (needs internet once, to download AGP/Kotlin/ZXing).
3. Run on **two physical Android phones (Android 8+)**, close together, Bluetooth ON.
4. Emulators do **not** support BLE advertising — physical devices only.

## How it works

- Every phone runs both BLE roles: it **advertises** (peripheral/GATT server) and
  **scans** (central/GATT client) at the same time.
- Tap a nearby person → one **1-to-1** chat connection.
- **Create a group** → your phone hosts it; others tap your entry to join.
  The host relays messages between members (star topology).
- Chats are **session-only**: everything lives in memory and is wiped when you
  disconnect or clear session data. Username, theme, language and notification
  settings are kept in SharedPreferences.

## Screens

- **Home** — Bluetooth toggle, connection status, Find Nearby button, identity card, settings
- **Nearby users** — live BLE discovery list + Create a group button
- **1-to-1 chat** / **Group chat** — message bubbles, send, disconnect / leave
- **My identity** — your name, session-identity note, change name
- **Settings** — theme (light/dark/system), language (EN/हिन्दी/ES), notifications,
  discoverable toggle, session-only note, clear session data, about
- **My QR** — QR code carrying your AirWave identity (`AIRWAVE|<name>`)

## BLE details

- Service UUID: `12345678-1234-5678-9abc-def012345678`
- Chat characteristic UUID: `...5679` (notify + write)
- Advertised name carries `AW-<username>`; manufacturer data marks AirWave packets
- Wire frames (UTF-8, `|` sanitized): `HELLO|sender|group|`, `BYE|sender|group|`,
  `SYS|sender|group|text`, `MSG|sender|group|senderTime|text`,
  `REPLY|sender|group|senderTime|quotedSender|quotedText|text`,
  `TYPING|sender|group|1|0`, `ACK|sender|group|senderTime`.
  `senderTime` is the sender's clock at send time; ACK echoes it back so the
  sender matches the exact message for ✓/✓✓. Both phones must run v2.0+ for
  ticks, typing and replies.
- MTU is negotiated to 512 for larger messages

## Notes / limits

- BLE is not encrypted here — traffic is plaintext. Fine for testing; do not
  treat it as secure messaging.
- Group relay is star-topology via the host; test with 3+ phones.
- Keep phones within ~10–20 m and awake while chatting.

## Fix history (2026-09-29)

- **One-way messages fixed:** the GATT server read `characteristic.value`
  instead of the write-request's `value` parameter on Android 12 and below,
  silently dropping every incoming message. The callback parameter is now
  used on all Android versions.
- **Duplicate messages fixed:** a 1-to-1 message now travels over exactly one
  transport (outgoing client connection preferred, otherwise the peer's
  connection to our server) instead of both at once.
- **Peer names fixed:** the username is applied to the Bluetooth adapter after
  permissions are granted, and every connection now exchanges a HELLO so the
  chat header shows the real username even if the adapter name didn't update.
- **Timestamps fixed:** message times were clipped when the text was shorter
  than the time; bubbles now size to fit both.
- Nearby list rescans when you return to it after connecting.
- Launcher icon and top-left logo replaced with the official AirWave artwork.

## v2.0 (2026-10-01)

### UI overhaul
- Clean, compact layout: tighter spacing, smaller cards/buttons, consistent 12–20dp rhythm
- New navigation drawer (menu button): Home, Nearby, Scan QR, Identity, My QR, Settings, About
- 24 new vector icons in a consistent 2dp-stroke style
- Activity transitions (slide) + list fall-down animation via theme window animations
- Home now shows a **Chats** list with avatars, snippets, times and unread badges
- Empty states for chats, nearby users and message lists
- Circular accent send button; compact peer rows; member chips slimmed down

### Features
- **QR scan-to-connect**: Nearby screen has a "Scan QR" button; scanning a person's
  `AIRWAVE|name` code connects and opens the chat, scanning a group code joins the group
- **Typing indicators**: "typing…" in 1-to-1 chats, "X is typing…" in groups
  (new `TYPING` frame; not stored, not notified)
- **Delivery ticks**: single ✓ / double ✓✓ on outgoing 1-to-1 messages
  (new `ACK` frame carrying the message timestamp)
- **Reply**: long-press a message → Reply shows a quote bar and sends a quoted message
  (new `REPLY` frame, relayed by group hosts like normal messages)
- **Copy / Delete**: long-press actions; delete removes the message locally
- **Unread badges**: per-chat and total badges on the home screen; chats are marked
  read when opened
- **Clear chat**: overflow menu clears one conversation
- **Block user**: overflow menu blocks/unblocks a peer (blocked messages are dropped)
- **Avatar colors**: pick your own avatar color on the Identity screen;
  peer avatars and sender names get stable per-name colors
- **About page**: logo, version, offline principles, feature list (from Settings)
- Group header: QR button (shows group join code) + overflow menu (Clear chat / Leave group)

### Protocol (additive only — existing BLE logic untouched)
- New frame types: `ACK`, `TYPING`, `REPLY`
- Group hosts relay `REPLY` frames exactly like `MSG`; ACKs are 1-to-1 only
- Delivery ticks: the ACK echoes the message's `senderTime`, so even rapid
  back-to-back messages match exactly — no timestamp guessing

## v3.0 (2026-10-01)

### Features
- **Image sharing**: 📷 button in any chat; photos are compressed to max 960px
  JPEG q65 and chunked over BLE (360-char base64 frames, ~25ms apart). Incoming
  images show a progress bar, then a thumbnail; tap for full-screen preview
  with save/share. Failed image sends get a retry icon.
- **Message reactions**: long-press → React → 👍 ❤️ 😂 😮 😢 🙏; reactions show
  under the message (tap the row to remove yours). Synced via `REACT` frames.
- **Pin messages**: long-press → Pin; pinned messages show a pin icon and are
  listed from the header pin button. Synced via `PIN` frames.
- **Chat search**: header search button opens a search bar; matches are
  highlighted and counted.
- **Mute chats**: overflow menu mutes/unmutes per-chat notifications (persisted).
- **Group admin / member roles**: the host is admin; admins get a 👑 chip and can
  promote/demote members or remove them (long-press a member chip).
- **Rename group** and **group avatar color**: admin-only, synced to all members.
- **Active / last-seen**: chat header subtitle shows "Active now" or
  "Last seen …" per peer.
- **BLE signal indicator**: 4-bar RSSI meter in 1-to-1 chat headers (polled).
- **Failed message retry**: messages that can't send stay in the list with a
  retry icon — tap to resend.
- **Export chat**: overflow menu exports the conversation as a text file
  (FileProvider share sheet).
- **Onboarding**: 3-page tutorial on first launch (skippable).
- **Connection history**: drawer → History shows this session's
  connects/disconnects/joins/kicks (session-only).
- **Group invite notifications**: nearby group advertisements trigger a
  notification (10-minute cooldown per group).
- **Leave group** still asks for confirmation.

### Protocol (additive only — existing BLE logic untouched)
- New frame types: `IMG_START`, `IMG_CHUNK`, `IMG_END`, `REACT`, `PIN`,
  `GNAME`, `GAVATAR`, `ROLE`, `KICK`
- `IMG_START|sender|group|imgId|totalChunks|senderTime|caption`
- `IMG_CHUNK|sender|group|imgId|index|base64` (360 chars, ~25ms pacing)
- `IMG_END|sender|group|imgId` — 1-to-1 images get a DM `ACK` echoing senderTime
- `REACT|sender|group|targetTime|emoji` (empty emoji = remove)
- `PIN|sender|group|targetTime|1|0`
- `GNAME|sender|groupId|newName`, `GAVATAR|sender|groupId|colorIndex`,
  `ROLE|sender|groupId|targetName|admin|member`, `KICK|sender|groupId|targetName`
- Group hosts relay image/reaction/pin frames exactly like `MSG`; admin frames
  are trust-checked (sender must be host or an admin) before applying/relaying.
- Both phones must run v3.0+ for images/reactions/pins/roles; older builds
  ignore the new frame types.

## v3.1 (2026-10-01)

- **Splash screen**: branded launch screen (app icon + "AirWave") for ~900ms.
  SplashActivity is now the LAUNCHER entry. On first run it routes to the
  name-entry page; afterwards straight to MainActivity.
- **Name-entry page replaces the tutorial**: the old 3-page onboarding tutorial
  is gone. First launch now shows a single clean page (app icon + in-app logo,
  name field, Continue button) that saves the display name and marks the user
  onboarded. `OnboardingActivity` removed.
- **Subtle corner radii**: cards/inputs/outlines refined to 12dp, chips 10dp,
  chat bubbles 14dp; all 22 themes set `dialogCornerRadius` 16dp.
- **21 named themes + System default** (22 options in Settings → Theme):
  13 dark (Dracula, Portfolio Dark, Dark 2026, Abyss, Dark (Visual Studio),
  Dark Modern, Dark+, Kimbie Dark, Monokai, Monokai Dimmed, Red,
  Solarized Dark, Tomorrow Night Blue), 6 light (Light 2026,
  Light (Visual Studio), Light Modern, Light+, Quiet Light, Solarized Light),
  2 high contrast (Dark High Contrast, Light High Contrast). Each theme sets
  its own palette; dark themes force night mode, light themes force day mode,
  System default follows the phone. Applied per-activity via
  `BaseActivity.setTheme()` + `ThemeRepo`.
- **7 languages**: English, हिन्दी, বাংলা, मराठी, తెలుగు, தமிழ், ગુજરાતી.
  Spanish removed. Settings → Language offers all 7; the 5 new languages
  ship full 210-string translations.
- **Nearby header branding**: the Nearby screen header now shows a centered
  lockup — app icon + in-app logo — instead of the text title.
- Version 3.1 (versionCode 5). No BLE/protocol changes; all UI work is
  additive.

## v3.2 (2026-10-01)
- **4-page onboarding**: the single name-entry screen is replaced by a 4-page
  onboarding flow — 3 animated tutorial pages + the name-entry page.
  Tutorial back by user request.
- **White onboarding pages**: tutorial pages use a white background by default,
  independent of the selected app theme.
- **Animated icons**: page 1 chat icon floats (translationY), page 2 has a
  full Bluetooth discovery scene (pulsing radar icon + expanding/fading pulse
  ring), page 3 group icon gently pulses. Animations run while the screen is
  visible and are cancelled on pause. Skip jumps to the name page; page dots
  and Next navigation included.
- Version 3.2 (versionCode 6). No BLE/protocol changes.

## v3.2.7 — stability + UI shape pass (no connectivity changes)

- **Readability fixes in chat.** Text on your own message bubbles and on
  badges used hardcoded white, which was unreadable on themes whose primary
  color is light (Dark HC, Monokai Dimmed, Tomorrow Night Blue). Everything
  sitting on a primary-colored surface now uses the theme's on-primary color.
  Quoted messages inside your own bubble got a translucent-white chip — the
  old grey quote box was illegible under white text.
- **Image sharing hardening.** Gallery photos are decoded off the UI thread
  and downsampled before being queued for BLE transfer, and chat thumbnails
  are decoded once into a memory cache instead of re-decoding the full file
  on every scroll. Fixes out-of-memory crashes on low-RAM phones and lag
  while scrolling image-heavy chats.
- **Keyboard "Send" works.** The message field advertised the keyboard's
  Send action but nothing handled it — now it sends. The theme picker also
  restarts the whole app so no back-stack screen is left in the old theme,
  and the Nearby screen no longer leaks its BLE listener after closing.
- **Consistent UI shapes (B1–B10 pass).** One corner-radius scale: 16dp cards
  and inputs, pill-shaped chips/badges/buttons, 18dp bubbles with an
  asymmetric 6dp tail. Outline buttons use a single 1.5dp stroke; primary
  buttons are uniformly 52dp tall; every icon-only control is at least 44dp;
  sub-page headers share one 48dp template with a consistent hairline
  divider; typography collapses to a 6-step scale; empty states share one
  template (64dp icon, bold title, secondary subtitle); all cards use 14dp
  internal padding. Message bubbles now cap at ~78% of screen width — the
  old 280dp limit sat on a view that ignores `maxWidth`, so it never worked.
  Image previews keep their aspect ratio instead of a fixed crop, member
  chips are density-correct, and the fall-down animation was removed from
  chat lists for a calmer feel.
- **Dead code removed.** 11 unused icons, 1 unused animation, a dead
  notification helper overload and 53 unused string keys across all 7
  languages (228 → 175 keys, full translation parity kept).
- AirWaveBle.kt (connectivity logic) is untouched in this release.
- Version 3.2.7 (versionCode 13).

## v3.2.6 — UI polish pass (no connectivity changes)

- **Fixed: two logos shown in the same spot.** The Nearby header showed the app
  icon next to the AirWave wordmark, and the onboarding name page stacked the
  app icon on top of the wordmark. Each place now shows a single logo.
- **Splash screen animation.** The logo now springs in with a soft glow ring
  that expands and fades, the wordmark and a new tagline rise into place, and
  the hand-off waits for the animation. Animations are cancelled on pause and
  a resume fallback never strands you on the splash.
- **Theme engine fixes.** All 21 named themes inherited the DayNight parent, so
  Material widgets could fall back to light-mode styling inside dark themes
  (and vice versa). Dark themes now use `Theme.Material3.Dark`, light themes
  `Theme.Material3.Light`; only "System default" follows day/night. The About
  page also showed a hardcoded "Version 2.0" — it now reads the real
  `versionName` from BuildConfig.
- **About page expanded.** New description, "How it works" steps and a Privacy
  card (no accounts, no phone number, no analytics, session-only chats), plus
  the existing Principles and Features lists. All new strings are translated
  into all 7 languages.
- **Clean-up of oversized UI.** Compacted the home status cards, the big
  Find-Nearby button, the chat input bar (smaller send button), the oversized
  QR card and title, chat screen paddings and the peer/empty-view avatar
  sizes.
- **Languages completed.** Bengali, Marathi, Telugu, Tamil and Gujarati were
  missing the 10 onboarding strings added in v3.2 (they silently fell back to
  English); all languages are now at full parity (228 keys).
- Version 3.2.6 (versionCode 12).

## v3.2.5 — chat delivery identity fixes

- **Fixed: messages arriving as notifications instead of in the chat.** A phone's
  BLE address depends on its role — the address you scan/dial (their advertiser)
  is usually not the address your GATT server sees when they dial you (their
  central). Conversations were keyed by that raw address, so the open chat and
  the incoming messages ended up in two different conversations. The server now
  aliases the two addresses together (learned from the HELLO handshake), merges
  any messages that landed under the raw address, and every frame, presence
  timestamp, ACK and connection callback is canonicalized before use.
- **Fixed: reconnect check opening a second GATT link.** `hasLinkTo()` was
  comparing the scanned address against the live link's central address and
  always missing; it is now alias-aware, so reopening a chat heals the existing
  link instead of stacking a duplicate one.
- **Fixed: members couldn't see the group member list.** Members never hear
  each other's HELLOs, so only the host saw everyone. The host now broadcasts a
  new `ROSTER` frame (names + roles) on join/leave/role-change, and members
  render their member list from it.
- **Fixed: unread badges depended on the notifications setting** — disabling
  notifications also disabled badges. Badges now always count; only the
  notification toast is gated.
- **Fixed: tapping a message notification opened Home.** Notifications now
  carry the conversation id and deep-link straight into the right chat or group
  (MainActivity is `singleTop` so `onNewIntent` routes while the app is open).
- **Fixed: group back button left the group.** Back now just navigates away;
  the BLE link and membership stay alive. Leaving stays in the overflow menu.
- **Fixed: dead activities stayed registered as the BLE listener** — listener
  is now cleared in `onDestroy`.
- **Hindi translation completed** — all 220 strings translated (was 74/220).

## v3.2.4 — build compatibility
- Removed the API-33 4-arg `onDescriptorWrite` overload: an unresolved merge conflict around it was producing bogus "overrides nothing" errors and breaking the build. The 3-arg override + 4s MTU fallback covers all phones (same approach as v3.2.1).
- Version 3.2.4 (versionCode 10).

## v3.2.3 — message transfer hardening
- Merged the API-33 4-arg `onCharacteristicChanged`/`onDescriptorWrite` overloads (the 4-arg descriptor-write callback removes the 4s handshake delay on modern phones).
- `onMtuChanged` now only marks the link ready on `GATT_SUCCESS` — a failed MTU exchange retries instead of leaving the link at 23 bytes with truncated frames.
- Connection failures are now logged to History with their status code.
- New BLE write queue: every outgoing frame is queued and sent one at a time as `onCharacteristicWrite` confirms each write. Previously, two overlapping writes (e.g. HELLO + first message) made the stack silently drop one with no error.
- Version 3.2.3 (versionCode 9).

## v3.2.2 — chat connection fixes
- Fixed: leaving the chat screen used to kill the Bluetooth link (`onDestroy` → `disconnectClient()`), and reopening the chat from Home never reconnected — so after pressing back once, no message could ever send. The link now survives leaving the chat (explicit Disconnect stays in the chat menu), and opening a chat auto-reconnects when no live link exists.
- Added handshake diagnostics to the History screen (drawer → History): "Peer service found, subscribing…", "Subscribed to peer updates", "Negotiating link speed…", "Link ready", "Couldn't send: link not ready yet". If messages still fail, a History screenshot pinpoints the exact stall.
- Version 3.2.2 (versionCode 8).

## v3.2.1 — BLE handshake fix
- Fixed a critical connection bug: the GATT setup used to fire the notification-subscribe and the MTU-size negotiation at the same instant. Android only allows one Bluetooth operation at a time and silently dropped the second one, so the link never became ready and stayed at the 23-byte minimum — no message could get through in either direction (1-to-1 or group).
- The setup steps are now properly chained (subscribe → MTU → ready) with automatic retries, so connections become fully ready a moment after tapping a peer.

## v3.2.1 — build fixes (from Android Studio)
- Escaped apostrophe in `tutorial_sub_2` (was breaking AAPT resource compilation).
- Named themes now parent `Theme.Material3.DayNight.NoActionBar` (the plain `Theme.Material3.NoActionBar` parent doesn't exist); night mode is still forced per-theme via `AirWaveApp.applyTheme()`, so each theme keeps its fixed palette.
- `onDescriptorWrite`: kept only the pre-33 override (the API 33+ 4-arg overload isn't in this project's compile SDK); the 4s MTU fallback covers newer phones.
- `R.attr.colorPrimary/colorOnSurface` → `com.google.android.material.R.attr.*` (the app declares no custom attrs, so app `R.attr` isn't generated).
- MainActivity: null-guard on `groupId` for `groupAvatarColor()`.
