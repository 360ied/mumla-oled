# Bug: DatagramSocket File Descriptor Leak During Server Pings

**Status:** confirmed bug / resource leak  
**Severity:** high (causes eventual `EMFILE: Too many open files` network failure)  
**Component:** `app` Networking / Favourite Server List  
**Files Affected:**
- [`ServerInfoTask.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/servers/ServerInfoTask.java)

---

## 1. Problem Description

When users view the **Favourite Servers** screen, Mumla asynchronously pings each server using `ServerInfoTask` to display latency, protocol version, and current user count.

In `ServerInfoTask.doInBackground()`:

```java
DatagramSocket socket = new DatagramSocket();
socket.setSoTimeout(1000);
socket.setReceiveBufferSize(1024);

long startTime = System.nanoTime();

socket.send(requestPacket);

byte[] responseBuffer = new byte[24];
DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
socket.receive(responsePacket);

int latencyInMs = (int) ((System.nanoTime()-startTime)/1000000);

ServerInfoResponse response = new ServerInfoResponse(server, responseBuffer, latencyInMs);
return response;
```

**`socket.close()` is never called anywhere in the task.** Neither a `try-with-resources` construct nor a `finally` block is used.

---

## 2. Technical Root Cause & Impact

1. **Leaking Linux File Descriptors:** Each invocation of `new DatagramSocket()` allocates an OS socket and associated file descriptor. Because it is never closed, the socket remains open until garbage collection finalization (which is non-deterministic and often delayed or omitted in modern ART).
2. **File Descriptor Exhaustion:** If a user has several saved servers, or frequently switches fragments or resumes the app, dozens of UDP sockets accumulate in the process. When the process hits the Android file descriptor limit (`RLIMIT_NOFILE`, typically 1024), subsequent network connections fail immediately with:
   ```
   java.net.SocketException: socket failed: EMFILE (Too many open files)
   ```
   This prevents new TCP connections to Mumble servers, DNS resolution, and audio streaming until the app process is terminated.

---

## 3. Remediation Plan

Refactor `ServerInfoTask.doInBackground()` to use standard Java `try-with-resources`:

```java
try (DatagramSocket socket = new DatagramSocket()) {
    socket.setSoTimeout(1000);
    socket.setReceiveBufferSize(1024);
    ...
    socket.send(requestPacket);
    socket.receive(responsePacket);
    ...
    return response;
} catch (Exception e) {
    Log.d(TAG, "Failed to ping server: " + e.getMessage());
}
```

This ensures the UDP socket descriptor is unconditionally closed immediately after the ping response is received or upon a timeout/exception.
