# Prompt for writing web apps targeting CouchFlow

Paste this entire document at the top of any AI chat (Claude, ChatGPT,
whatever) where you want the AI to write a web app that runs inside the
**CouchFlow** Android APK. Then add your specific app request underneath.

---

## What is CouchFlow?

A tiny Android APK that runs folders full of HTML/JS/CSS as if they were
native apps. The workflow is:

1. The user is on their couch with just a phone.
2. They ask an AI (you) to build a web app using this prompt.
3. You send back a zip.
4. They extract it on their phone and point CouchFlow at the folder.
5. The app is live — full-screen, no browser chrome, no CORS issues.

The APK takes care of running a localhost server, serving the folder,
routing filesystem access to the user's real disk, fixing broken WebView
APIs (like microphone recording), and caching large downloads
transparently. **The web app author shouldn't need to know any of this — it
just uses standard web APIs.**

The CouchFlow philosophy: if a feature requires the web app to know
CouchFlow exists, it probably doesn't belong here. Web apps use standard
Web APIs and CouchFlow makes them work. When you don't see something
documented below, **fall back to standard Web APIs and feature-detect** —
don't invent CouchFlow-specific methods.

---

## Environment guarantees

* **Runtime**: Android WebView, modern Chromium (roughly Chrome 110+ on
  current Samsung/Pixel devices).
* **Origin**: `http://localhost:<port>/`. **The port is stable per folder**
  — derived from a hash of the folder's URI. `localStorage`, `IndexedDB`,
  and OPFS all persist across launches.
* **Entry point**: `index.html`. Use relative paths (`./js/app.js`).
* **Shim is injected inline**: CouchFlow rewrites the HTML response to
  insert a `<script>` tag at the top of `<head>` **before** any user script
  runs. `window.AndroidFS`, the patched `MediaRecorder`, and the patched
  `fetch()` are all available on the first line of your first module.
* **`window.LWA.ready` / `window.CouchFlow.ready`**: resolves with
  `{features, version, isNative}` once shims are installed. The recommended
  way to check capabilities:
  ```js
  const { features } = await window.LWA.ready;
  if (features.includes('audio')) { /* ... */ }
  if (features.includes('share-files')) { /* ... */ }
  ```
  Both `window.LWA` and `window.CouchFlow` point to the same object.
* **Status & navigation bars adapt to the page**: include
  `<meta name="theme-color" content="#yourbg">` so CouchFlow tints both
  bars to match your page. You can also call
  `Android.setBarColor('#css-color')` at runtime when switching themes.
* **No build tools**. Import libraries directly from CDN:
  `import { foo } from 'https://esm.run/library'`
* **No service workers**. WebView doesn't support them reliably.
  CouchFlow provides transparent caching for large model downloads.
* **Viewport**: target mobile portrait, ~390 CSS px wide. Use
  `<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">`.
* **Permissions are requested upfront**: CouchFlow asks for microphone
  and camera the first time the user opens any web app, before the
  WebView even loads. You still need to feature-detect and handle the
  case where the user denies permission.

---

## In-app debug log

CouchFlow has a built-in debug log overlay. When something goes wrong, the
user opens it, taps **Copy**, and pastes the result back to the AI.

The log captures automatically:

* All `console.log/info/warn/error/debug` calls
* Uncaught errors and unhandled promise rejections
* Bridge method calls (`Android.*` and `AndroidFS.*`) with truncated args
* `fetch()` requests that go through the cache (URL, status, duration)
* Permission prompt outcomes
* `MediaRecorder` lifecycle events
* App lifecycle (load, navigate, pause, resume, low-memory)

**How users open it**: long-press the **+** FAB on the CouchFlow home
screen to enable a small debug button that appears bottom-right inside
every web app. Tap the button to see the log.

**Optional API for web apps**: push your own structured events into the
log so when the user shares it with their AI, the trace is more useful:

```js
// Surface major user actions and unexpected branches
window.CouchFlow?.debug?.('user-action', { type: 'tap-record', slide: 3 });
window.CouchFlow?.debug?.('parse-fallback', { reason: 'invalid json' });
```

Your app can also expose its own "Show debug log" button:

```js
if (window.CouchFlow?.openDebugLog) {
  myButton.onclick = () => window.CouchFlow.openDebugLog();
}
```

The log is in-memory only — it's gone when the app closes unless the user
hits **Save** in the overlay (writes to `Downloads/CouchFlow/`).

---

## The `Android` bridge (utilities)

Exposed as `window.Android` when running inside CouchFlow. Always
feature-detect so the same web app also works in a regular browser:

```js
if (typeof Android !== 'undefined' && Android.toast) Android.toast('Hi');
```

| Method                            | Returns     | Description                                      |
|-----------------------------------|-------------|--------------------------------------------------|
| `Android.toast(msg)`              | void        | Show a native Android toast                      |
| `Android.log(msg)`                | void        | Write to logcat under tag `CouchFlow`            |
| `Android.isNativeApp()`           | `true`      | Returns `true` when running in CouchFlow         |
| `Android.getPlatform()`           | `"android"` | Platform identifier                              |
| `Android.getAppVersion()`         | string      | CouchFlow's version name                         |
| `Android.share(text)`             | void        | Open the share sheet with a text payload         |
| `Android.vibrate()`               | void        | 100ms haptic buzz                                |
| `Android.getFreeMB()`             | string      | Free storage on CouchFlow's data dir in MB       |
| `Android.setBarColor(cssColor)`   | void        | Tint status/nav bars to match web-app theme      |
| `Android.openDebugLog()`          | void        | Open the debug log overlay programmatically      |

`Android.share(text)` is **text-only**. To share files, use the standard
Web Share API (see below) — CouchFlow's shim routes file shares through
native intents automatically.

---

## Sharing files (standard Web Share API)

CouchFlow patches `navigator.share()` to support `{files: Blob[]}`. Web
apps use the standard Web Share API and the OS share sheet opens with the
files attached.

```js
// Share a recorded audio clip
async function shareRecording(wavBlob) {
  const file = new File([wavBlob], 'recording.wav', { type: 'audio/wav' });
  if (navigator.canShare?.({ files: [file] })) {
    await navigator.share({
      title: 'Voice memo',
      text:  'Recorded just now',
      files: [file]
    });
  }
}
```

`navigator.canShare()` returns `true` when CouchFlow is hosting the page
and at least one of `text`, `url`, or `files` is supported. Outside
CouchFlow (browser fallback), behavior matches whatever the browser
provides natively.

Each file payload is capped at ~10 MB. For larger files, write them to
the picked folder via `AndroidFS` first; the user can then share from the
file manager.

---

## The `AndroidFS` bridge (real file system access)

Files are written to the folder the user picked — visible in their file
manager, survive app restart, survive uninstall-and-reinstall if they keep
the folder.

```js
await AndroidFS.read(path)             // → string | null
await AndroidFS.write(path, string)    // → boolean (creates parent dirs)
await AndroidFS.readBytes(path)        // → Uint8Array | null
await AndroidFS.writeBytes(path, u8)   // → boolean
await AndroidFS.delete(path)           // → boolean
await AndroidFS.list(path)             // → string[] (filenames in a dir)
await AndroidFS.exists(path)           // → boolean
await AndroidFS.mkdir(path)            // → boolean (creates nested dirs)
await AndroidFS.stat(path)             // → {size, mtime, isDir} | null
```

`stat()` is for building list views without reading every file's content
— gets you size, modification time (ms epoch), and whether it's a
directory. Use it instead of `read()` when you only need metadata.

### Examples

```js
// Persist a JSON document
await AndroidFS.write('notes/notes.json', JSON.stringify(notes, null, 2));

// Read it back on startup
const text = await AndroidFS.read('notes/notes.json');
const notes = text ? JSON.parse(text) : { notes: [] };

// Build a sorted list of saved memos by mtime — no content reads needed
const files = await AndroidFS.list('memos');
const items = await Promise.all(files.map(async (name) => {
  const s = await AndroidFS.stat(`memos/${name}`);
  return { name, mtime: s?.mtime ?? 0, size: s?.size ?? 0 };
}));
items.sort((a, b) => b.mtime - a.mtime);

// Save a binary file (e.g. a recorded audio clip)
await AndroidFS.writeBytes('clips/2025-04-22.wav', uint8array);
```

### Standard `fetch()` also works for writes

CouchFlow patches `fetch()` so standard web code persists to disk:

```js
await fetch('./notes/notes.json', { method: 'PUT', body: JSON.stringify(data) });
await fetch('./notes/notes.json', { method: 'DELETE' });
```

GET still reads from the HTTP server. Use whichever style you prefer.

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

### File-naming conventions

* Lowercase paths with hyphens, no spaces, no special characters.
* SAF can be case-sensitive on some backings and case-insensitive on
  others; `Foo.json` and `foo.json` may collide unpredictably.
* Use `.json`, `.wav`, `.txt`, `.png` etc. — extension matters for the
  user's file manager and for any future preview/share intent.

---

## Microphone recording — standard web APIs

Many Android WebViews have a known bug where
`getUserMedia({ audio: true })` fails with `NotReadableError`. CouchFlow
fixes this transparently — web apps just use standard APIs:

```js
const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
const recorder = new MediaRecorder(stream);
recorder.ondataavailable = e => handleBlob(e.data);  // WAV Blob
recorder.start();
recorder.pause();
recorder.resume();
recorder.stop();
```

Live waveform visualization works unchanged:

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

Recordings come back as **WAV** Blobs (`audio/wav`, PCM 16-bit 44.1 kHz
mono). Save via `AndroidFS.writeBytes` or share via `navigator.share`.

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

CouchFlow automatically caches downloads from common model hosts:

* `huggingface.co`, `cdn-lfs.*`, `cdn.jsdelivr.net`, `jsdelivr.net`,
  `esm.run`
* Any URL whose path contains `.onnx`, `.wasm`, `.bin`, `.safetensors`,
  `.gguf`, `.pt`, `.pth`, `.tflite`

First request downloads to disk; subsequent requests hit the cache
instantly. The cache lives in CouchFlow's app-private data directory,
not the user's picked folder. Use `Android.getFreeMB()` to monitor space
when downloading several GB of models, and consider exposing a "clear
model cache" button — the user can clear from CouchFlow's settings or
through Android's app info screen.

---

## Persistence priority

When you need to save data, choose by what kind of data it is:

1. **User-meaningful data** the user might inspect, sync, or back up
   (notes, recordings, projects) → `AndroidFS` to the picked folder.
   Visible in file manager. Survives uninstall + reinstall as long as
   the folder is kept.

2. **App state** (current view, scroll position, draft text, ephemeral
   preferences) → `localStorage`. Persists across launches because the
   port is stable per folder.

3. **Big derived caches** (rendered thumbnails, computed embeddings,
   anything regeneratable) → OPFS or IndexedDB. Both persist across
   launches.

4. **Downloaded models and binaries** → do nothing, CouchFlow caches
   them automatically.

---

## Defensive startup

AI-generated apps frequently crash on second launch because they wrote
partial state during a previous error. Always wrap parses in try/catch
and rebuild from defaults on failure:

```js
async function loadState() {
  try {
    const text = await AndroidFS.read('state.json');
    if (!text) return defaultState();
    return JSON.parse(text);
  } catch (e) {
    console.warn('state.json was corrupt, rebuilding', e);
    return defaultState();
  }
}
```

Same pattern for `localStorage`, `IndexedDB` reads, `JSON.parse` on
fetched configs — anywhere parse can fail.

---

## Permission denial handling

Permissions are requested upfront, but the user can deny them. Apps must
feature-detect and show a recoverable UI rather than crashing or
silently failing:

```js
async function startRecording() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    /* ... */
  } catch (err) {
    if (err.name === 'NotAllowedError') {
      showBanner('Microphone access denied. Open Android Settings → Apps → CouchFlow → Permissions to enable.');
    } else {
      showBanner(`Recording failed: ${err.message}`);
    }
  }
}
```

---

## Browser-mode testing

Your app should still work in a regular browser without CouchFlow. Open
`index.html` directly. Feature detection should provide a working
(degraded) path — if it doesn't, you've coupled too tightly to
CouchFlow.

```js
const inCouchFlow = !!window.CouchFlow;
if (inCouchFlow) {
  // Use AndroidFS.write
} else {
  // Fall back to localStorage or download via <a download>
}
```

---

## Layout and style conventions

* Target screen: ~390 CSS px wide mobile portrait
* Dark UI is recommended — include
  `<meta name="theme-color" content="#0F1626">` (or whatever your bg is)
  so CouchFlow can tint the status bar to match
* For runtime theme switching, call
  `Android.setBarColor('#newcolor')`
* Use `touch-action: manipulation` and
  `-webkit-tap-highlight-color: transparent` on tappable elements
* Use `user-select: none; -webkit-user-select: none` on controls
* Use `padding-top: env(safe-area-inset-top, 0)` and
  `padding-bottom: env(safe-area-inset-bottom, 0)` so content respects
  notches and the gesture bar

---

## Structure preferences

* Single `index.html` for tiny apps
* For larger apps, split into `css/styles.css` and `js/` modules
* Keep each JS file under ~500 lines
* Vanilla JavaScript or ES modules with CDN imports (`esm.run`,
  `jsdelivr`)
* No TypeScript, no JSX, no bundlers — CouchFlow serves files as-is
* The app ships as a zip. The user extracts to a folder on their phone
  and points CouchFlow at that folder.

---

## What NOT to do

* Don't use `file://` or `/android_asset/` URLs — WebView blocks them
* Don't use service workers — WebView won't register them reliably
* Don't hard-code absolute URLs like `http://localhost:39000/...` — use
  relative paths or `location.origin`
* Don't bundle large models with the web app — use `transformers.js` or
  similar and let CouchFlow cache them from HuggingFace
* `<link rel="manifest">` is harmless if you have one for browser-mode
  installability, but don't expect PWA install prompts inside CouchFlow

---

## WebView quirks (CouchFlow papers over these)

CouchFlow handles these for you, but knowing they exist helps when
something behaves oddly.

* **`getUserMedia({audio:true})` silently fails** on Samsung A55 /
  Android 14 and several other devices with `NotReadableError`. CouchFlow
  bypasses the WebView audio stack and uses Android's native
  `AudioRecord`. Standard `MediaRecorder` code works.
* **`ScriptProcessorNode` / `AudioWorklet` for mic capture** doesn't
  receive samples from CouchFlow's native stream. Use `AnalyserNode` with
  `getByteTimeDomainData` / `getFloatTimeDomainData` instead — those are
  patched.
* **AGC** isn't applied to native recordings; you'll get raw PCM at 44.1
  kHz 16-bit mono.
* **`MediaRecorder.pause()` / `resume()`** work properly — the native
  recorder drops samples while paused.
* **`SpeechRecognition`** is NOT provided by WebView. Use
  `transformers.js` with a Whisper model for offline speech recognition.
* **`navigator.vibrate(ms)`** works but `Android.vibrate()` is more
  consistent on some One UI builds.
* **WebView render process can crash** on out-of-memory. CouchFlow shows
  an error screen and pushes the crash to the debug log.

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
  <meta name="theme-color" content="#0F1626">
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
    try {
      const text = await AndroidFS.read('data/state.json');
      if (text) return JSON.parse(text);
    } catch (e) {
      console.warn('state.json corrupt, rebuilding', e);
    }
  }
  const local = localStorage.getItem('state');
  if (local) {
    try { return JSON.parse(local); } catch {}
  }
  return { items: [] };
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

Add a paragraph describing the app you want. Be concrete about:

* What it does
* What data it persists, and where
* What CouchFlow features it uses (mic, camera, file read/write, share)
* Preferred look and feel

Example:

> Build me a daily journaling app. Each entry has a title, a body, and an
> optional voice note. Entries are saved as individual JSON files in a
> `journal/` folder. Audio (if any) lives next to it as a WAV file. Dark
> UI, minimal, one entry shown at a time with swipe navigation. List
> view shows titles and dates. Add a "Share entry" button that uses
> navigator.share with the JSON + WAV as files.

---

*Last updated for CouchFlow shim v5: in-app debug log overlay,
`navigator.share` accepts files, `AndroidFS.stat()`, range/HEAD on
localhost server, capability detection via `LWA.ready`. The doc tracks
the installed runtime — when CouchFlow gains new capabilities, this
file is updated in the same commit.*
