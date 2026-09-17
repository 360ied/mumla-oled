# UI Interaction, Hardware & Peripheral Integration

This document details the user interface components, touch event handling, system overlays, hardware key capture, and peripheral integration for Push-to-Talk in Mumla OLED.

## Table of Contents

1. [In-App Push-to-Talk Button](#in-app-push-to-talk-button)
2. [Defect Deep-Dive: Stuck Microphone on Touch Cancellation (PTT-02)](#defect-deep-dive-stuck-microphone-on-touch-cancellation-ptt-02)
3. [Defect Deep-Dive: Button Height Display Density Bug (PTT-08)](#defect-deep-dive-button-height-display-density-bug-ptt-08)
4. [Defect Deep-Dive: Disappearing PTT Button on Mute (PTT-09)](#defect-deep-dive-disappearing-ptt-button-on-mute-ptt-09)
5. [Visual State Inconsistency: `setPressed` vs `setActivated` (PTT-10)](#visual-state-inconsistency-setpressed-vs-setactivated-ptt-10)
6. [PTT Hot Corner Overlay & The Soft-Keyboard Myth (PTT-13)](#ptt-hot-corner-overlay--the-soft-keyboard-myth-ptt-13)
7. [Hardware Keys, Peripherals & Background Limitations (PTT-11, PTT-12)](#hardware-keys-peripherals--background-limitations-ptt-11-ptt-12)
8. [Audio Feedback Deficiencies (PTT-14)](#audio-feedback-deficiencies-ptt-14)

---

## In-App Push-to-Talk Button

The main on-screen PTT button is defined in [`fragment_channel.xml:74-86`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/layout/fragment_channel.xml#L74-L86) and managed by [`ChannelFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java).

```xml
<LinearLayout
    android:id="@+id/pushtotalk_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center_horizontal"
    android:orientation="vertical">

    <Button
        android:id="@+id/pushtotalk"
        android:layout_width="match_parent"
        android:layout_height="50dp"
        android:text="@string/ptt"
        android:textColor="?android:attr/textColorPrimaryInverse"
        app:backgroundTint="@drawable/ptt_button_tint"
        app:cornerRadius="0dp" />
</LinearLayout>
```

---

## Defect Deep-Dive: Stuck Microphone on Touch Cancellation (PTT-02)

### The Bug in `ChannelFragment.java`

In [`ChannelFragment.java:160-178`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L160-L178):

```java
mTalkButton.setOnTouchListener(new View.OnTouchListener() {
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (getService() != null) {
                    getService().onTalkKeyDown();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (getService() != null) {
                    getService().onTalkKeyUp();
                }
                break;
        }
        return true;
    }
});
```

Notice that `onTouch` handles **only** `ACTION_DOWN` and `ACTION_UP`.
It completely ignores `MotionEvent.ACTION_CANCEL`.

### Real-World Failure Scenarios

On modern Android devices, touch gestures frequently trigger `ACTION_CANCEL`:
1. **Edge Back Gesture / Home Gesture**: Swiping in from the screen edge or up from the navigation bar while holding the PTT button cancels the touch event on the button.
2. **Notification Shade / Incoming Call**: Pulling down the status bar or receiving a heads-up notification / phone call causes the window manager to dispatch `ACTION_CANCEL`.
3. **Multi-Touch & Scroll Intercept**: If the user's thumb slides slightly into the adjacent `ViewPager` or chat list, the parent view can intercept touch dispatch, emitting `ACTION_CANCEL` to the child button.

### Impact

When `ACTION_CANCEL` is received:
- `ACTION_UP` is **never dispatched**.
- `getService().onTalkKeyUp()` is **never called**.
- The microphone remains **stuck in active transmission indefinitely**, broadcasting the user's ambient audio and private conversations to the entire channel until the user notices and taps the button again.

*(Contrast this with [`MumlaHotCorner.java:126-132`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java#L126-L132), where `ACTION_CANCEL` was explicitly implemented and handled).*

---

## Defect Deep-Dive: Button Height Display Density Bug (PTT-08)

### Preference Specification vs Runtime Execution

In [`settings_appearance.xml:62-72`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/xml/settings_appearance.xml#L62-L72), the PTT button height preference is defined with explicit `dp` units:

```xml
<se.lublin.mumla.preference.SeekBarDialogPreference
    android:key="pttButtonHeight"
    android:title="@string/pttButtonHeight"
    app:multiplier="10"
    app:max="100"
    app:min="15"
    android:defaultValue="15"
    android:text=" dp" />
```

The default value is $15 \times 10 = 150\text{ dp}$.

Now examine [`ChannelFragment.java:309-311`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L309-L311):

```java
ViewGroup.LayoutParams params = mTalkView.getLayoutParams();
params.height = settings.getPTTButtonHeight();
mTalkButton.setLayoutParams(params);
```

### Two Critical Implementation Flaws

1. **Raw Pixels vs Density-Independent Pixels**:
   In Android, `ViewGroup.LayoutParams.height` expects **raw physical pixels (px)**, not density-independent pixels (dp).
   On a modern 1080p or 1440p smartphone display:
   - Density is typically `xxhdpi` ($3.0\times$) or `xxxhdpi` ($3.5\times$ to $4.0\times$).
   - Passing raw `150` sets the button height to only $150\text{ px}$.
   - Converting to dp:
     ```math
     \text{Height (dp)} = \frac{150\text{ px}}{3.5} \approx 42.8\text{ dp}
     ```
   - This collapses the button below Google's official accessibility guideline for touch targets ($48\text{ dp}$), making it difficult to hit reliably. On an `mdpi` ($1.0\times$) tablet, 150px is a massive 150dp.
2. **Layout Parameter Hierarchy Violation**:
   `mTalkView` is the parent `LinearLayout` containing `mTalkButton`. Calling `mTalkView.getLayoutParams()` retrieves the layout params of the container (matching its parent, the root channel layout). Assigning those exact params to the child `mTalkButton.setLayoutParams(params)` corrupts layout attributes and fails to resize the parent container `mTalkView`.

---

## Defect Deep-Dive: Disappearing PTT Button on Mute (PTT-09)

In [`ChannelFragment.java:313-328`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L313-L328):

```java
boolean muted = false;
if (getService() != null && getService().isConnected()) {
    IUser self = null;
    try {
        self = getService().HumlaSession().getSessionUser();
    } catch (HumlaDisconnectedException|IllegalStateException e) {
        Log.d(TAG, "exception in configureInput: " + e);
    }
    muted = self == null || self.isMuted() || self.isSuppressed() || self.isSelfMuted();
}
boolean showPttButton =
        !muted &&
        settings.isPushToTalkButtonShown() &&
        settings.getInputMethod().equals(Settings.ARRAY_INPUT_METHOD_PTT);
setTalkButtonHidden(!showPttButton);
```

When `muted` is true, `setTalkButtonHidden(true)` sets `mTalkView.setVisibility(View.GONE)`.

### Why This is Detrimental

1. **Jarring Layout Shifts**:
   When the user taps "Self Mute", the PTT button abruptly disappears. The entire channel list / chat pager jumps downward to fill the vacant space. When unmuted, the button pops back into existence, jarring the user.
2. **Inconsistent with Hot Corner**:
   [`MumlaHotCorner.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java) **does not hide** when muted; it remains pinned to the corner.
3. **Better Paradigm**:
   Standard UI practice is to keep the button visible but **disable** it (`setEnabled(false)`) with reduced opacity and a mute icon, providing clear visual status rather than an unexpected UI collapse.

---

## Visual State Inconsistency: `setPressed` vs `setActivated` (PTT-10)

In [`ChannelFragment.java:98-107`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelFragment.java#L98-L107):

```java
switch (user.getTalkState()) {
    case TALKING:
    case SHOUTING:
    case WHISPERING:
        mTalkButton.setPressed(true);
        break;
    case PASSIVE:
        mTalkButton.setPressed(false);
        break;
}
```

### The Conflict

- In Android's `View` architecture, `pressed` state is managed directly by touch dispatch (`ACTION_DOWN` sets pressed = true; `ACTION_UP` sets pressed = false).
- In sticky/toggle mode (`PREF_PTT_TOGGLE`):
  1. The user taps the button.
  2. Lifting the finger dispatches `ACTION_UP`, causing Android to automatically reset `mTalkButton.setPressed(false)`.
  3. 30ms later, `onUserTalkStateUpdated()` fires from the audio engine and forcefully calls `mTalkButton.setPressed(true)`.
  4. This creates a visible **flicker / stutter** in button styling.
- The drawable selector [`ptt_button_tint.xml:6`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/res/drawable/ptt_button_tint.xml#L6) explicitly defines an activated state for sticky mode:
  ```xml
  <!-- Color when PTT button is in sticky mode, and activate -->
  <item android:state_activated="true" android:color="?attr/pttPressed" />
  ```
  Yet `ChannelFragment` never invokes `setActivated()`, leaving the XML selector rule unused.

---

## PTT Hot Corner Overlay & The Soft-Keyboard Myth (PTT-13)

### The Repository Claim

The project [`README.md:89`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/README.md#L89) states:

> **Re-Engineered PTT Hot Corner**: Latency-optimized push-to-talk corner with soft-keyboard awareness (automatically hides when typing) and vivid visual state feedback.

### The Source Code Reality

Inspecting the complete source of [`MumlaHotCorner.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaHotCorner.java) reveals:
- There is **no `WindowInsetsAnimation.Callback`**, no `ViewTreeObserver.OnGlobalLayoutListener`, and no `InputMethodManager` integration.
- There are no window flags (`FLAG_ALT_FOCUSABLE_IM`) or visibility toggles responding to IME keyboard visibility.
- When an on-screen keyboard opens (such as Gboard, SwiftKey, or Samsung Keyboard), the hot corner overlay **remains anchored on top of the keyboard**, obscuring key inputs (Enter, Backspace, or punctuation, depending on chosen corner gravity).

---

## Hardware Keys, Peripherals & Background Limitations (PTT-11, PTT-12)

### 1. Foreground-Only Key Capture (PTT-11)

Hardware keys are intercepted solely in [`MumlaActivity.java:448-464`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/MumlaActivity.java#L448-L464):

```java
@Override
public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (mService != null && keyCode == mSettings.getPushToTalkKey()) {
        mService.onTalkKeyDown();
        return true;
    }
    return super.onKeyDown(keyCode, event);
}
```

In Android, an `Activity` only receives key events when it is in the foreground with window focus.
- If the screen is locked, the phone is in a pocket, or the user switches to a navigation or game app, **physical PTT key presses are completely ignored**.
- If the user holds the key down and `MumlaActivity` pauses (`onPause()`), `onKeyUp` is never received, unbinding the service and risking a stuck transmission.

### 2. Missing Enterprise `KEYCODE_PTT` Support (PTT-12)

Android 10 (API 29) introduced [`KeyEvent.KEYCODE_PTT`](https://developer.android.com/reference/android/view/KeyEvent#KEYCODE_PTT) (keycode 286) as the standard platform keycode for dedicated Push-to-Talk buttons on rugged devices (e.g., CAT, Sonim, Zebra, Samsung Galaxy XCover).

Mumla contains **zero references** to `KEYCODE_PTT`. Users on enterprise devices must manually configure custom key bindings, which still fail when Mumla is backgrounded.

### 3. Missing Headset Hook / Media Button PTT (PTT-12)

Many users communicate using wired or Bluetooth headsets with inline call/play buttons (`KEYCODE_HEADSETHOOK` / `KEYCODE_MEDIA_PLAY_PAUSE`).

In [`MumlaConnectionNotification.java:202-215`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaConnectionNotification.java#L202-L215), `MediaSessionCompat` handles custom actions (`ACTION_MUTE`, `ACTION_DEAFEN`), but does **not override `onMediaButtonEvent()`**. Headset buttons cannot trigger PTT while backgrounded.

### 4. Handset Mode (Proximity Sensor) Conflict

In [`MumlaService.java:711-720`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L711-L720), handset mode acquires `PROXIMITY_SCREEN_OFF_WAKE_LOCK`. When the device is brought to the user's ear, the display powers off.
Because the display is off and `MumlaActivity` lacks window focus, all touch input and foreground key dispatch are terminated. PTT is impossible to use in Handset Mode unless engaged prior to raising the device.

---

## Audio Feedback Deficiencies (PTT-14)

In [`MumlaService.java:458-464`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/service/MumlaService.java#L458-L464):

```java
if (isConnectionEstablished() &&
        getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK &&
        user.getTalkState() == TalkState.TALKING &&
        mPTTSoundEnabled) {
    AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
    audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1);
}
```

### Three Major Limitations

1. **Dependency on Android Global Touch Clicks**:
   `AudioManager.playSoundEffect()` checks `Settings.System.SOUND_EFFECTS_ENABLED`. If the user has disabled touch sounds globally in Android system settings (as most users do to silence typing clicks), Mumla's PTT sound is **completely silent**, even with `ptt_sound` checked.
2. **Missing Deactivation / Release Cue**:
   Sound is played only when transitioning to `TALKING`. There is no release sound cue. Users releasing a hardware button or hot corner cannot verify audibly that transmission has stopped.
3. **Generic Click vs Radio Tone**:
   `FX_KEYPRESS_STANDARD` produces a faint keyboard click rather than a distinct two-tone radio chirp.
