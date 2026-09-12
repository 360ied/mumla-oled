# Bug: User Context Menu Ban Dialog Mislabeled as "Kick"

**Status:** confirmed UI bug / misleading action  
**Severity:** medium (user performs permanent ban thinking it is a temporary kick)  
**Component:** `app` UI / Context Menu  
**Files Affected:**
- [`UserMenu.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/UserMenu.java)

---

## 1. Problem Description

When an administrator taps a user in the channel list to open the context menu and selects **Ban** (`R.id.context_ban`), the confirmation dialog that appears is titled **"Kick"** and the positive action button is labeled **"Kick"**:

```java
if (itemId == R.id.context_ban || itemId == R.id.context_kick) {
    final EditText reasonField = new EditText(mContext);
    reasonField.setHint(R.string.hint_reason);
    new MaterialAlertDialogBuilder(mContext)
            .setTitle(R.string.user_menu_kick) // Always "Kick", never "Ban"
            .setView(reasonField)
            .setPositiveButton(R.string.user_menu_kick, (dialog, which) ->
                    mService.kickBanUser(mUser.getSession(), reasonField.getText().toString(), menuItem.getItemId() == R.id.context_ban))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
}
```

The underlying network call passes `menuItem.getItemId() == R.id.context_ban`, which correctly transmits a permanent server ban via `UserRemove.ban = true`.

However, the user interface completely misrepresents the action as a simple kick.

---

## 2. Technical Root Cause

The dialog builder hardcodes string resource `R.string.user_menu_kick` for both the dialog title and the positive button, failing to branch based on `itemId == R.id.context_ban`.

---

## 3. Remediation Plan

Conditionally select the title and button strings based on the clicked menu item:

```java
boolean isBan = menuItem.getItemId() == R.id.context_ban;
int titleRes = isBan ? R.string.user_menu_ban : R.string.user_menu_kick;
new MaterialAlertDialogBuilder(mContext)
        .setTitle(titleRes)
        .setView(reasonField)
        .setPositiveButton(titleRes, (dialog, which) ->
                mService.kickBanUser(mUser.getSession(), reasonField.getText().toString(), isBan))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
```
