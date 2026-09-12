# Broken Feature: Channel Description Editing Is an Unimplemented Stub

**Status:** confirmed unimplemented stub  
**Severity:** low-medium  
**Component:** `app` Channel Management  
**Files Affected:**
- [`ChannelDescriptionFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/comment/ChannelDescriptionFragment.java)
- [`AbstractCommentFragment.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/channel/comment/AbstractCommentFragment.java)

---

## 1. Problem Description

`AbstractCommentFragment` provides a tabbed dialog interface (with rich preview and HTML source editing) for modifying comments and descriptions.

While `UserCommentFragment` inherits from `AbstractCommentFragment` and properly updates user comments via `service.HumlaSession().setUserComment(...)`, `ChannelDescriptionFragment` leaves `editComment()` as an empty stub:

```java
public class ChannelDescriptionFragment extends AbstractCommentFragment {
    ...
    @Override
    public void editComment(IHumlaService service, String comment) {
        // TODO
    }
}
```

If channel description editing is triggered with `editing = true`, pressing **Save** executes this empty method and performs no action.

---

## 2. Technical Root Cause

`ChannelDescriptionFragment` was written to support viewing channel descriptions (`editing = false`), but the edit handler was left as a `TODO`. Additionally, channel description editing in `ChannelEditFragment` is also non-functional because editing channels as a whole is unimplemented.

---

## 3. Remediation Plan

Implement `editComment()` in `ChannelDescriptionFragment` to update the channel description over the Mumble protocol:

```java
@Override
public void editComment(IHumlaService service, String comment) {
    if (!service.isConnected())
        return;
    Mumble.ChannelState.Builder csb = Mumble.ChannelState.newBuilder();
    csb.setChannelId(getChannelId());
    csb.setDescription(comment);
    service.HumlaSession().getConnection().sendTCPMessage(csb.build(), HumlaTCPMessageType.ChannelState);
}
```
