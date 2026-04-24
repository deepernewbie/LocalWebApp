# Prompt for writing web apps targeting CouchFlow

Paste this entire document at the top of any AI chat (Claude, ChatGPT,
whatever) where you want the AI to write a web app that runs inside the
**CouchFlow** Android APK. Then add your specific app request underneath.

---

## What is CouchFlow?

A tiny Android APK that runs folders full of HTML/JS/CSS as if they were
native apps. The workflow for the person you're talking to (the author) is:

1. They're on their couch with just a phone.
2. They ask an AI (you) to build a web app using this prompt.
3. You send back a zip.
4. They extract it on their phone and point CouchFlow at the folder.
5. The app is live — full-screen, no browser chrome, no CORS issues.

The APK takes care of running a localhost server, serving the folder,
routing filesystem access to the user's real disk, fixing broken WebView
APIs (like microphone recording), and caching large downloads
transparently. **The web app author doesn't need to know any of this — it
just uses standard web APIs.**

---

## Environment guarantees

* **Runtime**: Android WebView, modern Chromium (roughly Chrome 110+ on
  current Samsung/Pixel devices)
* **Origin**: `http://localhost:<port>/`. **The port is stable per folder**
  — it's derived from a hash of the folder's URI. That means `localStorage`,
  `IndexedDB`, and OPFS all persist across app launches for the same folder.
  `localStorage.setItem('x', '1')` works exactly like you'd expect in any
  browser.
* **Entry point**: `index.html` in the picked folder. Paths inside the web
  app must be relative (`./js/app.js`, not `/js/app.js`). Root-absolute
  paths resolve to the server root, which is the same folder, so `/js/app.js`
  also works — but relative paths are more portable.
* **Shim is injected inline**: CouchFlow rewrites the HTML response to
  insert a `<script>` tag at the top of `<head>` **before** any user script
  runs. That means `window.AndroidFS`, the patched `MediaRecorder`, and the
  patched `fetch()` are all available on the first line of your first
  module. No race conditions, no polling, no `DOMContentLoaded` dance.
* **`window.LWA.ready` / `window.CouchFlow.ready`**: if you want an
  explicit signal that all shims are installed, `await window.LWA.ready`
  resolves with `{features, version, isNative}`. Only useful for advanced
  cases — in practice the shim is already active before any user code
  runs. `window.CouchFlow` is an alias of `window.LWA` for brand-aware
  apps; both refer to the same object.
* **Feature detection via HTML meta**: the injected `<head>` includes
  `<meta name="lwa-features" content="fs,audio,share,vibrate">` and
  `<meta name="couchflow-version" content="v3">` so static HTML /
  `<noscript>` blocks can detect the environment without running JS.
* **Status and navigation bars adapt to the page**: CouchFlow reads
  `<meta name="theme-color">` (or the computed `body` background color
  if there's no meta tag) and tints the Android system bars to match.
  Icon contrast adjusts automatically based on luminance. So if your page
  is dark, the status bar matches; if light, same. Include
  `<meta name="theme-color" content="#yourcolor">` in your HTML for the
  best result. You can also call `Android.setBarColor('#css-color')` at
  runtime to retint when switching themes without a reload.
* **No build tools**. Import libraries directly from CDN:
  `import { foo } from 'https://esm.run/library'`
* **No service workers**. WebView doesn't support them reliably. CouchFlow
  provides transparent caching for large model downloads (see below).
* **Viewport**: target mobile portrait, typically around 390px CSS pixels
  wide. Use `<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">`.
* **Permissions are requested upfront**: CouchFlow asks for microphone and
  camera permissions the first time the user opens any web app, before the
  WebView even loads. This means `getUserMedia()` calls don't need to be
  triggered by a user gesture to succeed, and the permission dialog won't
  interrupt the app's UI mid-flow. You still need to feature-detect and
  handle the case where the user denies permission.

---

## The `Android` bridge (general utilities)

Exposed as `window.Android` when running inside CouchFlow. Always
feature-detect before calling, so the same web app also works in a plain
browser:

```js
if (typeof Android !== 'undefined' && Android.toast) Android.toast('Hi');
```

### Methods

| Method                            | Returns     | Description                                      |
|-----------------------------------|-------------|--------------------------------------------------|
| `Android.toast(msg)`              | void        | Show a native Android toast                      |
| `Android.log(msg)`                | void        | Write to logcat under tag `CouchFlow`            |
| `Android.isNativeApp()`           | `true`      | Returns `true` when running in CouchFlow         |
| `Android.getPlatform()`           | `"android"` | Platform identifier                              |
| `Android.getAppVersion()`         | string      | CouchFlow's version name                         |
| `Android.share(text)`             | void        | Open the Android share sheet with `text`         |
| `Android.vibrate()`               | void        | 100ms haptic buzz                                |
| `Android.getFreeMB()`             | string      | Free storage on CouchFlow's data dir in MB       |
| `Android.setBarColor(cssColor)`   | void        | Tint status/nav bars to match web-app theme      |

---

## The `AndroidFS` bridge (real file system access)

**This is the right way to persist data.** Files are written to the folder
the user picked — visible in their file manager, survive app restart,
survive uninstall-and-reinstall if they keep the folder.

Exposed as `window.AndroidFS` after CouchFlow injects its shim at page load.
Every method returns a Promise. All paths are relative to the app's root
folder.

### Methods

```js
await AndroidFS.read(path)             // → string | null
await AndroidFS.write(path, string)    // → boolean (creates parent dirs)
await AndroidFS.readBytes(path)        // → Uint8Array | null
await AndroidFS.writeBytes(path, u8)   // → boolean
await AndroidFS.delete(path)           // → boolean
await AndroidFS.list(path)             // → string[] (filenames in a dir)
await AndroidFS.exists(path)           // → boolean
await AndroidFS.mkdir(path)            // → boolean (creates nested dirs)
```

### Examples

```js
// Persist a JSON document
await AndroidFS.write('notes/notes.json', JSON.stringify(notes, null, 2));

// Read it back on startup
const text = await AndroidFS.read('notes/notes.json');
const notes = text ? JSON.parse(text) : { notes: [] };

// Save a binary file (e.g. a recorded audio clip)
await AndroidFS.writeBytes('clips/2025-04-22.wav', uint8array);

// List saved clips
const files = await AndroidFS.list('clips');
```

### Standard `fetch()` also works for writes

CouchFlow patches `fetch()` so standard web code also persists to disk:

```js
// This writes ./notes/notes.json on disk
await fetch('./notes/notes.json', {
  method: 'PUT',
  body: JSON.stringify(data)
});

// DELETE removes the file
await fetch('./notes/notes.json', { method: 'DELETE' });
```

GET still reads from the HTTP server as usual. Use whichever style you
prefer — both hit the same files.

### Feature detection

```js
const hasFS = typeof window !== 'undefined'
           && window.AndroidFS
           && typeof window.AndroidFS.read === 'function';

if (hasFS) {
  await AndroidFS.write('data.json', json);
} else {
  // Fall back to OPFS or localStorage when running in a browser
  localStorage.setItem('data', json);
}
```

---

## Microphone recording — use standard web APIs

**Important:** many Android WebViews (Samsung A55 + Android 14, OnePlus Nord,
Pixel 3a+ emulator) have a known bug where `getUserMedia({ audio: true })`
fails with `NotReadableError: Could not start audio source` even though the
OS permission is granted.

CouchFlow fixes this transparently: it injects a shim at page load that
replaces `navigator.mediaDevices.getUserMedia` and `MediaRecorder` so they
route through Android's native `AudioRecord` under the hood. **Web apps just
use standard web APIs** — no CouchFlow-specific code needed.

### What works

```js
const stream = await navigator.mediaDevices.getUserMedia({ audio: true });

const recorder = new MediaRecorder(stream);
recorder.ondataavailable = e => handleBlob(e.data);       // WAV Blob
recorder.onstart   = ()  => console.log('recording');
recorder.onstop    = ()  => console.log('stopped');
recorder.onpause   = ()  => console.log('paused');
recorder.onresume  = ()  => console.log('resumed');

recorder.start();
recorder.pause();
recorder.resume();
recorder.stop();
```

### Live waveform visualization also works

```js
const ctx      = new AudioContext();
const source   = ctx.createMediaStreamSource(stream);
const analyser = ctx.createAnalyser();
analyser.fftSize = 2048;
source.connect(analyser);

const buf = new Uint8Array(analyser.frequencyBinCount);
function draw() {
  analyser.getByteTimeDomainData(buf);
  requestAnimationFrame(draw);
}
draw();
```

### Audio format

Recordings come back as **WAV** Blobs (`audio/wav`, PCM 16-bit 44.1kHz mono).
Every HTML `<audio>` element and the Web Audio API accept these directly.
To save:

```js
recorder.ondataavailable = async (e) => {
  const buf = new Uint8Array(await e.data.arrayBuffer());
  await AndroidFS.writeBytes(`clips/${Date.now()}.wav`, buf);
};
```

---

## Camera, geolocation, file picker

These work through standard WebView permission prompts:

* `navigator.mediaDevices.getUserMedia({ video: true })` — camera
* `navigator.geolocation.getCurrentPosition(...)` — GPS
* `<input type="file" accept="image/*" capture="environment">` — camera
  capture via the file picker
* `<input type="file">` — gallery or file chooser

---

## Transparent caching of large downloads

CouchFlow automatically caches any download from common model hosts:

* `huggingface.co`, `cdn-lfs.*`, `cdn.jsdelivr.net`, `jsdelivr.net`,
  `esm.run`
* Any URL whose path contains `.onnx`, `.wasm`, `.bin`, `.safetensors`,
  `.gguf`, `.pt`, `.pth`, `.tflite`, `.ot`

Web apps just use regular `fetch()` or library loaders (`transformers.js`,
`@huggingface/transformers`, etc.). First request downloads to disk,
subsequent requests hit the cache instantly.

---

## Permissions that are already declared

CouchFlow's manifest declares:

* `RECORD_AUDIO`, `CAMERA`
* `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
* `VIBRATE`
* `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`
* `INTERNET`

---

## Layout and style conventions

* Target screen: ~390 CSS px wide mobile portrait
* Dark UI is recommended — include
  `<meta name="theme-color" content="#0A0A0F">` (or whatever your page's
  bg is) so CouchFlow can tint the status bar to match
* Use `touch-action: manipulation` and `-webkit-tap-highlight-color:
  transparent` on tappable elements to avoid double-tap zoom and blue flash
* Use `user-select: none; -webkit-user-select: none` on controls
* Edge-to-edge: CouchFlow draws behind the status bar, so pages go
  full-bleed. Leave ~30px top padding for content that shouldn't sit under
  the clock and battery icon. Use
  `padding-top: env(safe-area-inset-top, 30px)` for safety.
* Haptics: prefer `Android.vibrate()` over `navigator.vibrate(ms)` when
  available — feels more consistent

---

## Structure preferences

* Single `index.html` for tiny apps
* For larger apps, split into `css/styles.css` and `js/` modules
* Keep each JS file under ~500 lines
* Vanilla JavaScript or ES modules with CDN imports (`esm.run`, `jsdelivr`)
* No TypeScript, no JSX, no bundlers — CouchFlow serves files as-is
* The app ships as a zip. The user extracts it to a folder on their phone
  and points CouchFlow at that folder

---

## Persistence strategy checklist

When you need to save data, use this priority:

1. **User data the user created** → `AndroidFS.write()` to the app's folder
   on disk. Visible in the user's file manager, survives everything
   including CouchFlow uninstall/reinstall as long as the folder is kept.
2. **Small user preferences** → `localStorage`. Persists across launches
   because the port is stable per folder.
3. **Larger derived caches** → OPFS or IndexedDB. Both persist across
   launches because the origin is stable.
4. **Downloaded models and large binaries** → do nothing, CouchFlow caches
   them automatically.

---

## What NOT to do

* Don't use `file://` or `/android_asset/` URLs — WebView blocks them
* Don't use `<link rel="manifest">` or expect PWA install prompts
* Don't use service workers — WebView won't register them reliably
* Don't hard-code absolute URLs like `http://localhost:39000/...` — the
  exact port is a stable hash but can vary by one or two if there's a
  conflict. Use relative paths (`./foo.js`) or `location.origin`
* Don't bundle models with the web app unless they're small — use
  `transformers.js` or similar and let CouchFlow cache them from HuggingFace

---

## Known WebView quirks (things you don't need to work around, but should know about)

CouchFlow papers over several Chromium WebView bugs — but the underlying
behavior is worth understanding if you hit an edge case.

* **`getUserMedia({audio:true})` silently fails** on Samsung A55 / Android
  14, OnePlus Nord, Pixel 3a+ emulator and others with `NotReadableError:
  Could not start audio source`. CouchFlow bypasses the WebView audio
  stack entirely and uses Android's native `AudioRecord`.
* **`ScriptProcessorNode` returns zero samples** for media streams from
  `getUserMedia` in affected WebViews. Use `MediaRecorder` (native-backed)
  and `AnalyserNode` (patched) instead of `AudioWorklet` for mic capture.
* **AGC (Automatic Gain Control) can clip loud sounds** in some WebView
  builds. CouchFlow's native recorder doesn't apply AGC.
* **`MediaRecorder.pause()` / `resume()`** are genuinely supported by
  CouchFlow.
* **`SpeechRecognition` / `webkitSpeechRecognition`** is NOT provided by
  WebView. Use `transformers.js` with a Whisper model for offline speech
  recognition. Models get cached automatically.
* **`navigator.vibrate(ms)`** works but `Android.vibrate()` is more
  consistent — the former may be silently ignored on some One UI builds.

---

## Example skeleton

```
my-app/
├── index.html
├── css/
│   └── styles.css
├── js/
│   ├── app.js
│   └── storage.js
└── data/              ← created at runtime by AndroidFS
    └── state.json
```

**index.html**
```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
  <meta name="theme-color" content="#0A0A0F">
  <title>My App</title>
  <link rel="stylesheet" href="./css/styles.css">
</head>
<body>
  <div id="app"></div>
  <script type="module" src="./js/app.js"></script>
</body>
</html>
```

**js/storage.js**
```js
const hasFS = typeof window !== 'undefined'
           && window.AndroidFS
           && typeof window.AndroidFS.read === 'function';

export async function loadState() {
  if (hasFS) {
    const text = await AndroidFS.read('data/state.json');
    if (text) { try { return JSON.parse(text); } catch {} }
  }
  const local = localStorage.getItem('state');
  return local ? JSON.parse(local) : { items: [] };
}

export async function saveState(state) {
  const text = JSON.stringify(state, null, 2);
  localStorage.setItem('state', text);
  if (hasFS) await AndroidFS.write('data/state.json', text);
}
```

---

## Deliverable format

Package the finished web app as a zip with the folder at the root:

```
my-app.zip
└── my-app/
    ├── index.html
    ├── css/
    └── js/
```

---

## Put your coding request below this line

After this document, add a single paragraph describing the app you want to
build. Be concrete about:

* What it does
* What data it needs to persist
* What CouchFlow features it uses (mic, camera, file read/write, etc.)
* Preferred look and feel

Example:

> Build me a daily journaling app. Each entry has a title, a body, and an
> optional voice note. Entries are saved as individual JSON files in a
> `journal/` folder. The audio for each entry (if any) lives next to it as
> a WAV file. Dark UI, minimal, one entry shown at a time with swipe
> navigation between them. List view shows titles and dates.

---

*Last updated: matches CouchFlow with shim v3 (inline HTML injection,
stable per-folder port, `window.LWA.ready` / `window.CouchFlow.ready`,
adaptive status bar, `<meta name="couchflow-version">`). When CouchFlow
gains new capabilities, this doc should be updated to reflect them.*
