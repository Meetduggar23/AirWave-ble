<p align="center">
  <img src="app/src/main/res/drawable-nodpi/appicon2.png" width="140" alt="AirWave logo">
</p>

<h1 align="center">AirWave</h1>

<p align="center"><b>Connect. Chat. Offline.</b></p>

<p align="center">
  Private offline chat over Bluetooth Low Energy.<br>
  No internet. No servers. No accounts. No cloud.
</p>

---

## What is AirWave?

AirWave is an Android app that lets nearby phones chat with each other over **Bluetooth Low Energy (BLE)** — with zero internet connection. Every phone simultaneously advertises its presence and scans for others, so two phones within Bluetooth range can start chatting instantly, no setup needed.

It is built for situations where the internet is unavailable, unreliable, or simply not wanted: classrooms, campuses, travel, events, and areas with poor network coverage.

## Features

### Messaging
- **1-to-1 chat** — tap any nearby person to start a private conversation
- **Group chat** — create a group and host it; members join via tap or QR code, messages relay through the host (star topology)
- **Rich messages** — text, photos, voice notes, and files
- **Replies, reactions, and pins** — quote a message, react with emoji, pin important messages
- **Delivery ticks (✓/✓✓)**, typing indicators, and read receipts
- **Message search**, per-chat mute, and chat export

### WhatsApp-style experience, offline
- **Home chat list** — all conversations in one place with avatars, last-message previews, timestamps, and unread badges; tap any chat to open it — it reconnects automatically when the person is nearby
- **Message anyone from a group** — tap a member's name or chip to open a private 1-to-1 chat with them
- **Forward messages** between chats, create polls in groups

### Groups & admin
- Group QR invite codes, live member roster with roles
- Admin controls: rename group, change group avatar color, promote/demote admins, remove members
- Disappearing messages (24h / 7 days)

### Privacy by design
- **Session-only chats** — conversations live on your phone and are wiped when you disconnect or clear the session
- No accounts, no phone numbers, no cloud backup, no analytics
- Optional app lock (PIN) to protect your chats

### Personalization
- **22 themes** including dark, light, and high-contrast options
- **7 languages**: English, हिन्दी, বাংলা, मराठी, తెలుగు, தமிழ், ગુજરાતી
- Custom avatar colors, per-contact colors

### Smart connectivity
- **Auto-reconnect** — reopening a chat redials the peer when they're nearby; group members auto-reconnect to the host
- **Connection history** — session log of connects, disconnects, joins, and kicks
- **QR connect** — scan a person's or group's QR code to connect instantly
- **Block users**, **last-seen / active-now** presence, BLE signal-strength meter

## How it works

- Each phone runs **both BLE roles**: peripheral (advertises + GATT server) and central (scans + GATT client).
- 1-to-1 chats use a direct GATT connection; group messages are relayed by the host.
- Photos are compressed and chunked over BLE; voice notes and files use chunked transfer with resume-safe framing.
- Chats persist locally on-device (Room database) so history survives app restarts.

## Requirements

- **Two physical Android phones** (Android 8.0+) with Bluetooth on, within ~10–20 m of each other
- Emulators do **not** support BLE advertising — physical devices only

## Build & run

1. Open this folder in **Android Studio** (Hedgehog or newer).
2. Let Gradle sync (needs internet once, to download the Android Gradle Plugin, Kotlin, and ZXing).
3. Run on two physical phones, Bluetooth ON.
4. On first launch, enter your display name — then tap **Find Nearby Users** and tap a person to chat, or create a group.

## Project

Built as a BCA dissertation project at **Symbiosis Institute of Computer Studies & Research (SICSR)**, Symbiosis International (Deemed University), Pune.

- **Meet Duggar** (23030124198)
- **Daksh Rohra** (23030124183)
- Guide: **Madhu Ma'am**

> **Note:** AirWave traffic is plaintext over BLE — fine for a college project and local testing, but not secure messaging. It does not defeat jamming and does not guarantee connectivity in every situation.
