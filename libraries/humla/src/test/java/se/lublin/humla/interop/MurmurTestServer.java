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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages one throwaway {@code mumble-server} for an interop test class.
 *
 * <p>Each instance picks a free loopback port, writes a temporary
 * {@code murmur.ini}, copies the committed loopback test certificate and key
 * into the temp dir, sets the SuperUser password via {@code -readsupw}
 * (password on standard input, never on the command line), starts
 * {@code mumble-server -fg -ini}, and waits until the port accepts.
 * {@link #stop()} terminates the server. Callers should stop in a finally
 * (or JVM shutdown hook) so no server outlives its class.
 *
 * <p>When {@code mumble-server} is not on {@code PATH} the test is skipped
 * (assumption failure), so plain unit-test runs without the nix dev shell
 * stay green.
 */
public final class MurmurTestServer {
    private static final String SUPERUSER_PASSWORD = "interop-superuser-pw";
    private static final String SERVER_PASSWORD = "interop-server-pw";

    private final Path dir;
    private final int port;
    private final boolean withServerPassword;
    private Process process;

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
        Path dir = Files.createTempDirectory("murmur-interop-");
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        MurmurTestServer server = new MurmurTestServer(dir, port, withServerPassword);
        server.launch();
        return server;
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

    private void launch() throws Exception {
        copyResource("/murmur/test-cert.pem", dir.resolve("test-cert.pem"));
        copyResource("/murmur/test-key.pem", dir.resolve("test-key.pem"));

        Path db = dir.resolve("murmur.sqlite");
        Path ini = dir.resolve("murmur.ini");
        StringBuilder sb = new StringBuilder();
        sb.append("host=127.0.0.1\n");
        sb.append("port=").append(port).append("\n");
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
        drain(p);
        boolean exited = p.waitFor(30, TimeUnit.SECONDS);
        assumeTrue("mumble-server -readsupw must exit promptly", exited && p.exitValue() == 0);

        List<String> cmd = new ArrayList<>();
        cmd.add("mumble-server");
        cmd.add("-fg");
        cmd.add("-ini");
        cmd.add(ini.toString());
        ProcessBuilder server = new ProcessBuilder(cmd);
        server.redirectErrorStream(true);
        process = server.start();
        // Do not block on the server's output; drain it on a daemon thread so
        // a chatty server can never block on a full pipe.
        Thread drainer = new Thread(() -> drain(process), "murmur-drain");
        drainer.setDaemon(true);
        drainer.start();

        waitForPort();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    private void waitForPort() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            if (process != null && !process.isAlive()) {
                throw new IOException("mumble-server exited during startup");
            }
            try {
                new Socket(host(), port).close();
                return;
            } catch (IOException expected) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("mumble-server did not open port " + port, expected);
                }
                Thread.sleep(200);
            }
        }
    }

    public void stop() {
        Process p = process;
        process = null;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            p.destroyForcibly();
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
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
