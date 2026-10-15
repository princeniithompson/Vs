# VoxStream Production Text Inserter & Direct Injection Pipeline

A robust, production-grade text insertion engine for VoxStream based on Whisperflow's hybrid architecture. It ensures dictated text is always directly injected into the active text field with caret positioning, leaving the system clipboard completely untouched, and safely falling back to the clipboard with subtle ring feedback only when no editable field exists.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following core architectural and UX decisions were confirmed in Phase 1:
> - **Injection Orchestration (Hybrid Approach)**: Combined Accessibility Service hierarchy resolution (`AccessibilityNodeInfo`, `ACTION_SET_TEXT`, `ACTION_SET_SELECTION`) and caret-level text splicing, paired with mutex concurrency locking to prevent race conditions during rapid speech streaming.
> - **Clipboard Contamination Prevention**: System clipboard is strictly isolated and never touched during normal dictation; `fallbackToClipboard` executes only when all direct injection paths confirm no active editable field exists on screen.
> - **Fallback Notification**: Silent copy with a subtle floating ring visual pulse (amber/cyan breathing indicator) instead of disruptive system toasts or jarring alerts.

- **Confirmed Decision 1**: Hybrid direct injection model prioritizing active caret node insertion, accurate cursor preservation, and hint stripping.
- **Confirmed Decision 2**: Silent clipboard copy accompanied by a brief micro-animation on the floating Lifesaver ring when no focused text field is available.

---

## 1. Overview & Core Concept

- **What It Does**: Replaces fragmented injection paths with a dedicated `VoxStreamTextInserter` component. When a dictation session finalizes, the inserter locates the focused text container, calculates cursor position, preserves any pre-existing text or user drafts, executes direct insertion via accessibility node actions, and reports a structured `InsertionResult` (`SuccessDirect`, `SuccessClipboardFallback`, `Failed`).
- **Target Audience / Persona**: Mobile voice dictation users who type across diverse Android applications (WhatsApp, Telegram, Gmail, Notion, Slack, mobile browsers, terminal emulators) and require seamless in-place typing without their clipboard history being overwritten.
- **Key Value**: 100% clipboard cleanliness during normal typing, elimination of clobbered drafts, and smooth, non-intrusive fallback handling.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Direct Caret Injection (Primary CUJ)**:
   - User focuses any input field in any app (e.g. WhatsApp chat input).
   - User dictates via floating ring or main app; Gemini Live transcribes in real-time.
   - Upon session completion or tap "Insert", `VoxStreamTextInserter.insertTranscript()` runs inside a coroutine mutex.
   - Text inserts cleanly at the exact cursor position without modifying the clipboard.
   - The floating ring displays a brief green success glow and gently docks.

2. **No Text Field / Fallback CUJ**:
   - User dictates without having an editable text field focused (e.g., reading a PDF or on the home screen).
   - Direct injection checks fail to find an active editable node.
   - `VoxStreamTextInserter` routes to `fallbackToClipboard()`.
   - Text is securely copied to the clipboard.
   - The floating ring triggers a subtle amber ripple animation (`FallbackIndicator`) indicating the text is ready to paste, without displaying an intrusive full-screen toast.

### Visual Identity & Theme
- **Subtle Ring Feedback**:
  - Success Direct: Quick electric cyan / teal pulse (`#58A6FF` / `#2EA043`).
  - Fallback Clipboard: Warm amber / gold breathing ring (`#D29922`).
- **Haptic Feedback**: Single gentle tick on direct success; double micro-tick on fallback.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Mutex-Protected Insertion Engine (`Mutex.withLock`)**:
  - *Chosen Approach*: Wrap all insertion attempts in a coroutine `Mutex` inside `VoxStreamTextInserter`.
  - *Why*: Eliminates race conditions when the user taps quickly, speaks in short bursts, or when interim/final transcript events overlap.
  - *Alternatives Considered*: Atomic flags alone, which can cause dropped insertions or interleaved text strings.

- **Decision 2: Comprehensive Editable Node Resolution with Hint Preservation**:
  - *Chosen Approach*: Combine `AccessibilityService.findFocus(FOCUS_INPUT)`, `window.findFocus()`, and root-level DFS traversal to reliably locate the active field across custom WebViews and Jetpack Compose fields.
  - *Why*: Many modern Android apps (e.g. Chrome, Discord, Slack) use custom views where standard single-node checks fail.

- **Decision 3: Silent Feedback on Fallback**:
  - *Chosen Approach*: Animate the floating ring's outer halo rather than displaying Android system Toasts.
  - *Why*: Toasts linger for several seconds, obstruct bottom app navigation, and interrupt fluid workflow.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                   Dictation Session / Floating Popup                   │
│                                                                        │
│  User completes speech -> triggers onTranscriptFinalized(text)         │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                       VoxStreamTextInserter                            │
│  - insertionMutex.withLock { ... }                                     │
│                                                                        │
│  Step A: tryDirectInjection(transcript)                                │
│          │                                                             │
│          ├── 1. Locate focused editable node via AccessibilityService  │
│          ├── 2. Extract genuine text (strip placeholders, keep drafts) │
│          ├── 3. Calculate cursor splice (textSelectionStart / End)     │
│          └── 4. node.performAction(ACTION_SET_TEXT, args)              │
│                 └── node.performAction(ACTION_SET_SELECTION, endArgs)  │
│                                                                        │
│          Success? ──► Return InsertionResult.SuccessDirect             │
│          No?      ──► Fallback path                                    │
│                                                                        │
│  Step B: fallbackToClipboard(transcript)                               │
│          │                                                             │
│          ├── Copy to system ClipboardManager                           │
│          └── Trigger subtle FloatingRing amber feedback                │
│                                                                        │
│          Return InsertionResult.SuccessClipboardFallback               │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                      InjectionLogRepository                            │
│  - Logs structured InjectionEvent for diagnostics and performance trace │
└────────────────────────────────────────────────────────────────────────┘
```

### Data Model & Component Interfaces
- `VoxStreamTextInserter`:
  - `suspend fun insertTranscript(transcript: String): InsertionResult`
  - `private fun tryDirectInjection(text: String): Boolean`
  - `private fun fallbackToClipboard(text: String): Boolean`
- `InsertionResult`:
  - `object SuccessDirect : InsertionResult`
  - `object SuccessClipboardFallback : InsertionResult`
  - `object Failed : InsertionResult`
- `FloatingRingVisualState`:
  - `IDLE`, `LISTENING`, `INJECTING_SUCCESS`, `CLIPBOARD_COPIED`
