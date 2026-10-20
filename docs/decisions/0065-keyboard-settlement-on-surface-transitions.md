# ADR-0065: Keyboard settlement on surface transitions

## Status

Accepted, implemented in `presentation/SoftKeyboardAware`,
`presentation/MainActivity.settleKeyboardFor`, the six surface views
(`DesktopHomeView`, `AppFormView`, `WebAppSurfaceView`, `TerminalAppView`,
`LogsScreenView`, `SystemScreenView`), and
`WebAppSurfaceView.showSelectedTab`. Covered by `MainActivityKeyboardTest`
(Robolectric, through `ShadowInputMethodManager`'s recorded visibility) and
`SoftKeyboardContractTest` (the declaration and the four transition call
sites). Real-IME evidence remains a device matter — the shadow records that
the hide was requested, not that an OEM keyboard routed it.

## Context

The shell is one Activity with retained surfaces that swap visibility, so the
soft keyboard has to be settled by the host on every transition — the
framework does not do it for a `GONE`/`VISIBLE` flip. The pre-change code had
one helper, `dismissLauncherInput`, called from three of the four transition
paths: returning to the launcher (`showShell`, reached by Back, the task-bar
Home action, and every form callback) dismissed nothing, so a keyboard opened
in the form or a web app rode across and covered the launcher. The helper also
returned early when `getCurrentFocus()` was null — exactly the state an IME
raised for a WebView field can leave behind — and a focused view whose window
token is already null makes `hideSoftInputFromWindow` silently no-op.

The user's framing asked for a per-page declaration of where the keyboard
belongs. Only four places host text input: the launcher search field, the
app form, a web-app surface (page fields plus the sign-in card), and a
terminal surface. System and Logs never do.

## Decision

1. **`SoftKeyboardAware.ownsTextInput()`** declares per surface whether text
   input lives in it. It does *not* gate the dismiss — the host always hides
   the keyboard on a surface switch — it tells the host whether focus inside
   the surface is legitimate. A surface that owns no input gets its focus
   swept on entry; input-owning surfaces are not swept, so whatever focus they
   keep internally — a WebView's focused field, a half-filled form — stays
   theirs to manage across a trip to the launcher and back.
2. **`settleKeyboardFor(View)` runs before the visibility switch** in all four
   transitions (`showShell`, `showSurface`, `showWebApp`, `showTerminalApp`).
   Ordering matters: input-owning surfaces re-establish focus when shown —
   `AppFormView.bindNew` focuses its first field, a shown terminal re-focuses
   its bridge — so settling first never costs them focus, and no screen opens
   mid-typing.
3. **The window token falls back to the decor view's** when the focused view
   is absent or detached, which is the state the old early-return leaked.
4. **In-surface tab switches clear the outgoing WebView's focus**, so a hidden
   tab's DOM-focused field cannot keep the keyboard open while another tab
   renders.

No destination auto-opens the keyboard on entry, including the form: the
field is focused and one tap raises it, but the keyboard starts closed.

## Consequences

- Keyboard dismissal is a host policy, not a per-screen habit: a new surface
  is covered automatically, and a surface that adds text input declares it
  instead of wiring IME calls of its own.
- `ShadowInputMethodManager` does not validate window tokens, so the
  focused-token vs decor-token distinction is device-verification territory
  (`docs/test-plan.md` X-12…X-14); the JVM tests prove the call happens.
- Deliberately out of scope: auto-showing the keyboard when a form opens, and
  policing IME inside a shown surface (a WebView that drops field focus
  mid-page is the page's own concern).
