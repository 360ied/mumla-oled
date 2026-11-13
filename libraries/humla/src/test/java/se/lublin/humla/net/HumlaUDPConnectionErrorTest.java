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

package se.lublin.humla.net;

import junit.framework.TestCase;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Pins the socket-failure arm of the UDP contract: an {@link java.io.IOException}
 * while opening the socket (host resolve, create, or connect) must reach
 * {@link HumlaUDP.UDPConnectionListener#onUDPConnectionError}, which is what
 * makes {@code LegacyVoiceLink} emit {@code VoiceEvent.UdpUnavailable} and the
 * transport tunnel at once (see {@code docs/target-architecture/voice.md}).
 *
 * <p>The failure is provoked hermetically with a {@code .invalid} host (RFC
 * 6761: guaranteed to not resolve, and it fails fast without touching the
 * network) rather than by blocking a real UDP route, which has no loopback
 * seam.
 */
public class HumlaUDPConnectionErrorTest extends TestCase {
    private static final String UNRESOLVABLE_HOST = "humla-udp-error-test.invalid";

    public void testUnresolvableHostReportsConnectionError() throws Exception {
        CryptState crypt = new CryptState();
        List<Exception> errors = new CopyOnWriteArrayList<>();
        CountDownLatch reported = new CountDownLatch(1);
        HumlaUDP.UDPConnectionListener listener = new HumlaUDP.UDPConnectionListener() {
            @Override
            public void onUDPDataReceived(byte[] data) {}

            @Override
            public void onUDPConnectionError(Exception e) {
                errors.add(e);
                reported.countDown();
            }

            @Override
            public void resyncCryptState() {}
        };
        // Direct executor: the callback runs on the UDP coroutine, no hop.
        Executor direct = Runnable::run;
        HumlaUDP udp = new HumlaUDP(crypt, listener, direct);
        try {
            udp.connect(UNRESOLVABLE_HOST, 64738);
            assertTrue("no UDP connection error reported within 10 s",
                    reported.await(10, TimeUnit.SECONDS));
            assertEquals("exactly one failure report per failed socket", 1, errors.size());
        } finally {
            udp.disconnect();
        }
    }
}
