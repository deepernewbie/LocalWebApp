<div align="center">

# CouchFlow

**Prompt a web app. Unzip. Tap. Done.**

![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-blue?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)
![Made For](https://img.shields.io/badge/made%20for-couch%20coders-8B5CF6?style=flat-square)

</div>

---

## What is this

CouchFlow is a tiny Android APK that runs folders full of HTML/JS/CSS as if
they were native apps — full-screen, no browser chrome, no CORS, no install
dance. You point it at a folder and it just runs.

But the real trick is how you *get* those folders:

1. You're on your couch with your phone.
2. You open an AI chat (Claude, ChatGPT, whatever).
3. You paste CouchFlow's [`prompt.md`](./prompt.md) as a system prompt.
4. You describe the app you want: *"a voice journal with Whisper
   transcription, each entry as its own file, swipeable"*.
5. The AI sends you a zip.
6. You extract the zip on your phone. Tap the CouchFlow icon. Pick the
   folder. Your app is live.

The prompt teaches the AI everything about CouchFlow's runtime — file
system API, microphone access, permissions, layout conventions — so the
code it generates just works. No iteration, no "you missed the..." emails
to yourself.

---

## Why this is useful

Mobile web apps hit real walls:
- Random localhost ports break `localStorage` across sessions
- WebView audio is broken on several popular phones (`NotReadableError`)
- No filesystem access means user data lives in volatile origin storage
- CORS blocks third-party model downloads

CouchFlow fixes all of this, transparently, without the web-app author
needing to know the APK exists. Standard `fetch()`, standard
`MediaRecorder`, standard `localStorage` — they all just work, and they all
persist to real disk that the user can see in their file manager.

---

## Install

Grab the latest APK from Actions — check the Actions tab for signed release APKs.

---

## Use it

### As a user

1. **Install the APK.**
2. **Get a web app.** Ask an AI to build one using the [prompt
   template](./prompt.md), or grab one from the
   [examples/](./examples) folder.
3. **Extract it** anywhere on your phone (Downloads works).
4. **Open CouchFlow**, tap the **+** button, pick the folder.
5. **Optional**: from the project list, tap the pin icon to add a
   home-screen shortcut. Now the app launches directly.

### As a web app author

Drop this at the top of your AI chat:

> [`prompt.md`](./prompt.md) (paste the whole thing, then add your request
> below it)

Write your app with standard web APIs:

```js
// File persistence
await AndroidFS.write('notes/2024-01.json', JSON.stringify(entry));
const entries = await AndroidFS.list('notes');

// Audio recording — works even on phones where getUserMedia is broken
const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
const rec = new MediaRecorder(stream);
rec.ondataavailable = e => saveBlob(e.data);  // WAV
rec.start();

// Toast, vibrate, share
Android.toast('Saved!');
Android.vibrate();
Android.share('check this out');
```

No APIs to import, no SDK to install. The shim is injected inline into
your HTML before any of your scripts run.

---

## What's inside

The APK:

- **Full-screen WebView** with edge-to-edge rendering. Status and
  navigation bars tint to match your page's `<meta name="theme-color">`
  or `body` background automatically — no blue system bar crashing your
  dark UI.
- **Localhost HTTP server** serving your folder. The port is a stable
  hash of the folder URI, so `localStorage`, OPFS, and IndexedDB all
  persist across launches.
- **Storage Access Framework** folder picker. Your app's files live in
  the real filesystem — visible in the file manager, syncable with cloud
  drives, survives uninstall/reinstall if you keep the folder.
- **Transparent native microphone** via Android's `AudioRecord`. Bypasses
  the known Chromium WebView bug (`NotReadableError: Could not start
  audio source`) that affects Samsung A55, OnePlus Nord, Pixel emulators,
  and others. Live waveform, pause/resume, WAV output — all standard Web
  Audio code works unchanged.
- **Transparent model cache** for HuggingFace, jsDelivr, and other
  common hosts. `.onnx`, `.wasm`, `.safetensors`, `.gguf`, `.bin` files
  download once and stick around. No CORS headaches either.
- **`window.AndroidFS`** — Promise-based file API for reading, writing,
  listing, deleting. Works with standard `fetch('./path', {method:
  'PUT', body: data})` too.
- **Home-screen shortcuts** for any loaded project. Colored letter
  icons, direct launch, no picker.

All of this is exposed to the web app through a thin JS shim that lies
under standard Web APIs. The web app author can pretend CouchFlow
doesn't exist.

---

## The prompt

[`prompt.md`](./prompt.md) is the heart of the project. It's the
document you hand to an AI so it knows how to write code that targets
CouchFlow's runtime. Paste it, add your request, get back code that
works first try.

It covers:
- Environment guarantees (stable port, inline shim, upfront permissions)
- Every bridge method with examples
- Known WebView quirks the APK papers over
- Persistence strategy checklist
- Layout and style conventions
- What NOT to do

When the APK's capabilities change, the prompt gets updated too — so the
document always matches the installed runtime.

---

## Example apps

See [`examples/`](./examples) for ready-made projects. Pick one, extract
it on your phone, point CouchFlow at the folder.

- **voice-memo** — Whisper-powered voice notes with transcription,
  saved as JSON to disk
- **quizlens** — local vision-language model that generates quiz
  questions from what your camera sees
- *more coming — PRs welcome*

---

## Architecture

```
┌──────────────────────────────────┐
│   User's SAF-picked folder       │
│   ├── index.html                 │
│   ├── js/, css/                  │
│   ├── notes/   ← persisted       │
│   └── models/  ← user-managed    │
└──────────────┬───────────────────┘
               │ served on stable port
               ▼
┌──────────────────────────────────┐
│   Localhost HTTP server          │
│   + HTML shim injection          │
│   + network cache interceptor    │
└──────────────┬───────────────────┘
               │
               ▼
┌──────────────────────────────────┐
│   WebView (full-screen)          │
│   ├── AudioRecord bridge  ← fixes NotReadableError
│   ├── DocumentFile bridge ← real disk I/O
│   ├── Permission plumbing ← upfront grant
│   └── Status-bar sync     ← reads theme-color
└──────────────────────────────────┘
```

---

## Contributing

Bug reports, example apps, and capability requests welcome. The goal is
a runtime that's **small, predictable, and invisible**. If a feature
requires the web app to know CouchFlow exists, it probably doesn't
belong here.

---

## License

MIT. Do whatever. Credit appreciated, not required.

---

<div align="center">

**Built for software enthusiasts who prefer laying on a couch.**

</div>
