# Broken Feature: Server Ban and User List Administration APIs Throw UnsupportedOperationException

**Status:** confirmed unimplemented stubs  
**Severity:** medium  
**Component:** `libraries/humla` Protocol / Server Administration  
**Files Affected:**
- [`HumlaService.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/HumlaService.java)
- [`IHumlaSession.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/libraries/humla/src/main/java/se/lublin/humla/IHumlaSession.java)

---

## 1. Problem Description

The Mumble protocol defines protocol buffers for querying and updating server-wide administration lists:
- `Mumble.BanList` (for viewing and editing server IP/hash bans)
- `Mumble.UserList` (for managing registered user accounts on the server)

In `HumlaService.java` (lines 1004–1012), the corresponding service methods are exposed on `IHumlaSession`, but their implementations unconditionally throw runtime exceptions:

```java
@Override
public void requestBanList() {
    throw new UnsupportedOperationException("Not yet implemented"); // TODO
}

@Override
public void requestUserList() {
    throw new UnsupportedOperationException("Not yet implemented"); // TODO
}
```

Any code invoking these methods will crash the application with an unhandled `UnsupportedOperationException`.

---

## 2. Technical Root Cause & Status

These methods were declared during the initial architecture of the `humla` library, but neither the protobuf handler in `ModelHandler` nor any corresponding activity or fragment in `app` was ever created.

---

## 3. Remediation Plan

1. **If Full Administration Features Are Desired:**
   - Implement `messageBanList(Mumble.BanList msg)` and `messageUserList(Mumble.UserList msg)` in `ModelHandler`.
   - Implement `requestBanList()` and `requestUserList()` in `HumlaService` by transmitting `BanList` and `UserList` queries over TCP.
   - Build UI fragments in `app` to display and manipulate the ban list and user list.
2. **If Server Administration UI Is Out of Scope:**
   - Remove these dead methods from `IHumlaSession` and `HumlaService` to prevent unexpected runtime crashes, or safely no-op them with appropriate logging.
