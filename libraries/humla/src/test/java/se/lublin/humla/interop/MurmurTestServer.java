/*
 * Copyright (C) 2026 Brian Zhu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.interop;

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages one throwaway {@code mumble-server} per test method.
 *
 * <p>Each instance picks a free loopback port (retrying on bind collisions),
 * writes a temporary {@code murmur.ini}, copies the committed loopback test
 * certificate and key into the temp dir, sets the SuperUser password via
 * {@code -readsupw} (password on standard input, never on the command line),
 * starts {@code mumble-server -fg -ini}, and waits until the port accepts.
 * {@link #stop()} terminates the server and removes its shutdown hook.
 *
 * <p>When {@code mumble-server} is not on {@code PATH} the test is skipped
 * (assumption failure), so plain unit-test runs without the nix dev shell
 * stay green. A present-but-broken binary fails instead of skipping.
 */
public final class MurmurTestServer {
    private static final String SUPERUSER_PASSWORD = "interop-superuser-pw";
    private static final String SERVER_PASSWORD = "interop-server-pw";
    private static final int MAX_START_ATTEMPTS = 3;

    private final Path dir;
    private final int port;
    private final boolean withServerPassword;
    // Written on the test thread, read on the shutdown-hook thread: volatile
    // for visibility (stop() is idempotent, so a race is benign either way).
    private volatile Process process;
    private volatile Thread shutdownHook;
    private volatile Path serverLog;

    private MurmurTestServer(Path dir, int port, boolean withServerPassword) {
        this.dir = dir;
        this.port = port;
        this.withServerPassword = withServerPassword;
    }

    /** Starts a server without a server password. */
    public static MurmurTestServer start() throws Exception {
        return start(false);
    }

    /** Starts a server; when {@code withServerPassword} a password is required. */
    public static MurmurTestServer start(boolean withServerPassword) throws Exception {
        IOException lastBindFailure = null;
        for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
            Path dir = Files.createTempDirectory("murmur-interop-");
            int port;
            try (ServerSocket probe = new ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            MurmurTestServer server = new MurmurTestServer(dir, port, withServerPassword);
            try {
                server.launch();
                return server;
            } catch (IOException e) {
                // Bind collisions (stolen probe port, server gone before the
                // port opens) are retried; anything else fails immediately.
                if (!isBindCollision(e)) {
                    server.stop();
                    throw e;
                }
                lastBindFailure = e;
                server.stop();
            } catch (InterruptedException e) {
                // External interruption (JUnit timeout, worker kill): don't
                // leak the attempt dir, restore the flag, propagate.
                Thread.currentThread().interrupt();
                server.stop();
                throw e;
            } catch (RuntimeException | Error e) {
                // fail()/assumeTrue() from launch(): don't leak the attempt's
                // temp dir, then propagate (skip stays a skip, failure a failure).
                server.stop();
                throw e;
            }
        }
        throw new IOException(
                "mumble-server did not start after " + MAX_START_ATTEMPTS + " attempts",
                lastBindFailure);
    }

    private static boolean isBindCollision(IOException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("did not open port") || message.contains("exited during startup"));
    }

    public String host() {
        return "127.0.0.1";
    }

    public int port() {
        return port;
    }

    public String serverPassword() {
        return withServerPassword ? SERVER_PASSWORD : null;
    }

    public String superuserPassword() {
        return SUPERUSER_PASSWORD;
    }

    /**
     * Stops the server process but keeps the temp dir (config + SQLite),
     * then starts a fresh process on the same port. The client should
     * observe Reconnecting and then Connected once the port reopens.
     */
    public void restart() throws Exception {
        Process p = process;
        process = null;
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException | IllegalArgumentException ignored) {
            }
        }
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        // Relaunch on the same port with the same ini/database.
        Path ini = dir.resolve("murmur.ini");
        List<String> cmd = new ArrayList<>();
        cmd.add("mumble-server");
        cmd.add("-fg");
        cmd.add("-ini");
        cmd.add(ini.toString());
        ProcessBuilder server = new ProcessBuilder(cmd);
        serverLog = dir.resolve("mumble-server.log");
        server.redirectOutput(serverLog.toFile());
        server.redirectErrorStream(true);
        process = server.start();

        waitForPort();
        shutdownHook = new Thread(this::stop);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void launch() throws Exception {
        copyResource("/murmur/test-cert.pem", dir.resolve("test-cert.pem"));
        Path keyDest = dir.resolve("test-key.pem");
        copyResource("/murmur/test-key.pem", keyDest);
        // Test-only private key: keep it owner-readable where POSIX allows.
        try {
            Files.setPosixFilePermissions(
                    keyDest, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows CI): default ACLs apply.
        }

        Path db = dir.resolve("murmur.sqlite");
        Path ini = dir.resolve("murmur.ini");
        StringBuilder sb = new StringBuilder();
        sb.append("host=127.0.0.1\n");
        sb.append("port=").append(port).append("\n");
        // Pin the UDP port explicitly: modern murmur defaults it to `port`, and
        // the UDP scenarios should not depend on that default holding.
        sb.append("udpport=").append(port).append("\n");
        sb.append("database=").append(db.toString()).append("\n");
        sb.append("sslCert=").append(dir.resolve("test-cert.pem").toString()).append("\n");
        sb.append("sslKey=").append(dir.resolve("test-key.pem").toString()).append("\n");
        sb.append("welcometext=Interop test server\n");
        sb.append("users=20\n");
        if (withServerPassword) {
            sb.append("serverpassword=").append(SERVER_PASSWORD).append("\n");
        }
        Files.write(ini, sb.toString().getBytes(StandardCharsets.UTF_8));

        // Set the SuperUser password (password on stdin, never on the command line).
        ProcessBuilder supw = new ProcessBuilder("mumble-server", "-ini", ini.toString(), "-readsupw");
        supw.redirectErrorStream(true);
        Process p;
        try {
            p = supw.start();
        } catch (IOException e) {
            assumeTrue("mumble-server not on PATH; run via scripts/test_interop.sh", false);
            throw e;
        }
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write((SUPERUSER_PASSWORD + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
        // Wait before draining: drain() blocks until the child closes stdout,
        // so draining first would park here forever on a stuck child and the
        // timeout below would never fire.
        boolean exited = p.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            p.destroyForcibly();
            fail("mumble-server -readsupw did not exit within 30 s");
        }
        drain(p);
        if (p.exitValue() != 0) {
            fail("mumble-server -readsupw exited with " + p.exitValue()
                    + "; run via scripts/test_interop.sh with pkgs.murmur");
        }

        // Server output goes straight to a file in the attempt dir: no drainer
        // thread to race, no pipe that a chatty server can block on, and the
        // log survives for triage when startup fails. The dir (and the log
        // with it) is deleted by stop().
        List<String> cmd = new ArrayList<>();
        cmd.add("mumble-server");
        cmd.add("-fg");
        cmd.add("-ini");
        cmd.add(ini.toString());
        ProcessBuilder server = new ProcessBuilder(cmd);
        serverLog = dir.resolve("mumble-server.log");
        server.redirectOutput(serverLog.toFile());
        server.redirectErrorStream(true);
        process = server.start();

        waitForPort();
        shutdownHook = new Thread(this::stop);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void waitForPort() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            if (process != null && !process.isAlive()) {
                throw new IOException(
                        "mumble-server exited during startup; log tail: " + logTail());
            }
            try {
                new Socket(host(), port).close();
                return;
            } catch (IOException expected) {
                if (System.nanoTime() > deadline) {
                    throw new IOException(
                            "mumble-server did not open port " + port + "; log tail: " + logTail(),
                            expected);
                }
                Thread.sleep(200);
            }
        }
    }

    /** Last ~2 KB of the server log, best effort, for failure triage. */
    private String logTail() {
        if (serverLog == null || !Files.exists(serverLog)) {
            return "<no log>";
        }
        try {
            byte[] all = Files.readAllBytes(serverLog);
            int from = Math.max(0, all.length - 2048);
            return new String(all, from, all.length - from, StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
        } catch (IOException e) {
            return "<unreadable log>";
        }
    }

    public void stop() {
        Process p = process;
        process = null;
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException | IllegalArgumentException ignored) {
                // Already shutting down (or never registered): nothing to remove.
            }
        }
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        try {
            deleteRecursively(dir);
        } catch (IOException ignored) {
            // Best effort: temp dirs never fail a test.
        }
    }

    private static void copyResource(String name, Path dest) throws IOException {
        try (InputStream in = MurmurTestServer.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("test resource missing: " + name);
            }
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void drain(Process p) {
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            while (in.read(buf) != -1) {
                // Discard; keeps the server from blocking on a full pipe.
            }
        } catch (IOException ignored) {
            // Server exited or pipe closed; nothing to do.
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
