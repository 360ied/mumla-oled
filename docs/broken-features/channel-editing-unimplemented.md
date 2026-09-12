# Broken Feature: Channel Editing Discards Changes & Empty Position Crashes

**Status:** confirmed bug / unimplemented stub  
**Severity:** high (silent data loss & unhandled crash)  
**Component:** `app` UI / Channel Management  
**Files Affected:**
- [`ChannelEditFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelEditFragment.java)
- [`ChannelMenu.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/ChannelMenu.java)

---

## 1. Problem Description

When an authorized user attempts to edit an existing channel via the channel context menu, the application exhibits multiple severe failures:

1. **Input fields are never initialized:** In `ChannelMenu.java` (lines 111–120), selecting "Edit" launches `ChannelEditFragment` with `adding = false` and `channel = mChannel.getId()`. In `ChannelEditFragment.onCreateDialog()`, the view elements (`mNameField`, `mDescriptionField`, `mPositionField`, `mTemporaryBox`) are never pre-populated with the target channel's existing values. The user is presented with blank fields.
2. **Edits are silently discarded (no-op stub):** In `ChannelEditFragment.java` (lines 85–95), the positive dialog button click listener contains:
   ```java
   .setPositiveButton(isAdding() ? R.string.add : R.string.save, (dialog, which) -> {
       if (isAdding() && mServiceProvider.getService() != null && mServiceProvider.getService().isConnected()) {
           mServiceProvider.getService().HumlaSession().createChannel(getParent(),
                   mNameField.getText().toString(),
                   mDescriptionField.getText().toString(),
                   Integer.parseInt(mPositionField.getText().toString()),
                   mTemporaryBox.isChecked());
       } else {
           // TODO
       }
   })
   ```
   When editing (`!isAdding()`), the handler hits `else { // TODO }`. The dialog simply dismisses and the user's modifications are discarded without making any network call or updating the server.
3. **Crash on empty position field:** `Integer.parseInt(mPositionField.getText().toString())` assumes that because `InputType` is `numberSigned`, the string is guaranteed to be a valid integer. If the user clears the position field or leaves it empty, submitting the dialog throws an unhandled `java.lang.NumberFormatException: s == null || s.length() == 0`, crashing the application.

---

## 2. Technical Root Cause

- `ChannelEditFragment` was partially scaffolded for channel creation (`isAdding() == true`), but channel modification (`ChannelState` update) was left as a `TODO` comment.
- No retrieval logic was implemented in `onCreateDialog()` to query `mServiceProvider.getService().HumlaSession().getChannel(getChannel())` and populate the `EditText` fields.
- Input validation in the positive button lacks an empty-check or `try-catch` guard around `Integer.parseInt()`.

---

## 3. Remediation Plan

1. **Pre-populate existing channel data in `onCreateDialog`:**
   - When `!isAdding()`, fetch the channel via `session.getChannel(getChannel())`.
   - Set initial text on `mNameField`, `mDescriptionField`, and `mPositionField`.
   - Check `mTemporaryBox` if the channel is temporary.
2. **Implement channel modification in the positive button:**
   - Construct and send a `ChannelState` update message via `IHumlaSession` (e.g. `session.editChannel(getChannel(), name, description, position, temporary)` or directly through `sendTCPMessage`).
3. **Harden position parsing:**
   - Safely parse the position string with a fallback to `0` (or the existing channel position) if empty or malformed.
