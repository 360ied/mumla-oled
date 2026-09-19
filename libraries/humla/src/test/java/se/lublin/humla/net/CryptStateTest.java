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

import java.util.Arrays;

/**
 * Unit tests verifying OCB-AES128 cryptographic state, IV resynchronization,
 * and replay detection in {@link CryptState}.
 */
public class CryptStateTest extends TestCase {

    private static final byte[] TEST_KEY = new byte[]{
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10
    };

    private static final byte[] CLIENT_IV = new byte[]{
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20
    };

    private static final byte[] SERVER_IV = new byte[]{
            0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30
    };

    public void testSetKeysAndValidity() throws Exception {
        CryptState cryptState = new CryptState();
        assertFalse(cryptState.isValid());

        cryptState.setKeys(TEST_KEY, CLIENT_IV, SERVER_IV);
        assertTrue(cryptState.isValid());
        assertTrue(Arrays.equals(CLIENT_IV, cryptState.getEncryptIV()));
        assertTrue(Arrays.equals(SERVER_IV, cryptState.getDecryptIV()));

        for (int i = 0; i < 256; i++) {
            assertEquals(0, cryptState.mDecryptHistory[i]);
        }
    }

    public void testEncryptDecryptRoundtrip() throws Exception {
        // Sender encrypts with CLIENT_IV
        CryptState sender = new CryptState();
        sender.setKeys(TEST_KEY, CLIENT_IV, SERVER_IV);

        // Receiver decrypts with CLIENT_IV as its decrypt IV
        CryptState receiver = new CryptState();
        receiver.setKeys(TEST_KEY, SERVER_IV, CLIENT_IV);

        byte[] plain = "Hello Mumble Voice Packet!".getBytes();
        byte[] encrypted = sender.encrypt(plain, plain.length);

        byte[] decrypted = receiver.decrypt(encrypted, encrypted.length);
        assertNotNull(decrypted);
        assertTrue(Arrays.equals(plain, decrypted));
        assertEquals(1, receiver.mUiGood);
    }

    public void testSetDecryptIVClearsHistory() throws Exception {
        CryptState receiver = new CryptState();
        receiver.setKeys(TEST_KEY, SERVER_IV, CLIENT_IV);

        // Manually populate decrypt history
        Arrays.fill(receiver.mDecryptHistory, (byte) 0x55);

        byte[] newNonce = new byte[]{
                (byte) 0xA0, (byte) 0xA1, (byte) 0xA2, (byte) 0xA3,
                (byte) 0xA4, (byte) 0xA5, (byte) 0xA6, (byte) 0xA7,
                (byte) 0xA8, (byte) 0xA9, (byte) 0xAA, (byte) 0xAB,
                (byte) 0xAC, (byte) 0xAD, (byte) 0xAE, (byte) 0xAF
        };

        receiver.setDecryptIV(newNonce);

        assertTrue(Arrays.equals(newNonce, receiver.getDecryptIV()));
        for (int i = 0; i < 256; i++) {
            assertEquals(0, receiver.mDecryptHistory[i]);
        }
    }

    public void testReplayDetectionRejectsDuplicate() throws Exception {
        CryptState sender = new CryptState();
        sender.setKeys(TEST_KEY, CLIENT_IV, SERVER_IV);

        CryptState receiver = new CryptState();
        receiver.setKeys(TEST_KEY, SERVER_IV, CLIENT_IV);

        byte[] plain = "Test audio payload".getBytes();
        byte[] encrypted = sender.encrypt(plain, plain.length);

        // First decryption must succeed
        byte[] firstDecrypted = receiver.decrypt(encrypted, encrypted.length);
        assertNotNull(firstDecrypted);
        assertTrue(Arrays.equals(plain, firstDecrypted));

        // Immediate replay of the same packet must be rejected
        byte[] replayDecrypted = receiver.decrypt(encrypted, encrypted.length);
        assertNull(replayDecrypted);
    }

    public void testReplayCheckUsesDecryptIV1NotEncryptIV0() throws Exception {
        // Upstream CryptStateOCB2.cpp compares:
        // decrypt_history[decrypt_iv[0]] == decrypt_iv[1]
        //
        // Previous bug in Mumla compared against mEncryptIV[0] instead of mDecryptIV[1].
        // Verify that when mEncryptIV[0] matches the stored history entry, but decrypt_iv[1]
        // does not, the packet is correctly accepted as valid rather than dropped.
        CryptState sender = new CryptState();
        sender.setKeys(TEST_KEY, CLIENT_IV, SERVER_IV);

        CryptState receiver = new CryptState();
        receiver.setKeys(TEST_KEY, SERVER_IV, CLIENT_IV);

        // Encrypt packet 1
        byte[] plain1 = "Payload 1".getBytes();
        byte[] enc1 = sender.encrypt(plain1, plain1.length);
        assertNotNull(receiver.decrypt(enc1, enc1.length));

        // Encrypt packet 2
        byte[] plain2 = "Payload 2".getBytes();
        byte[] enc2 = sender.encrypt(plain2, plain2.length);

        // Record history entry from packet 1:
        int iv0 = receiver.mDecryptIV[0] & 0xFF;
        byte historyVal = receiver.mDecryptHistory[iv0];

        // Deliberately set receiver's mEncryptIV[0] to equal historyVal
        receiver.mEncryptIV[0] = historyVal;

        // Decrypting packet 2 must succeed despite mEncryptIV[0] matching historyVal
        byte[] decrypted2 = receiver.decrypt(enc2, enc2.length);
        assertNotNull("Packet must not be falsely dropped due to mEncryptIV[0] collision", decrypted2);
        assertTrue(Arrays.equals(plain2, decrypted2));
    }

    public void testDecryptPacketLossUnsignedByteHandling() throws Exception {
        CryptState sender = new CryptState();
        sender.setKeys(TEST_KEY, CLIENT_IV, SERVER_IV);

        CryptState receiver = new CryptState();
        receiver.setKeys(TEST_KEY, SERVER_IV, CLIENT_IV);

        // Set IVs to byte values >= 128 (0x80) where Java sign extension occurs if not masked
        sender.mEncryptIV[0] = (byte) 130; // -126 in Java byte
        receiver.mDecryptIV[0] = (byte) 130;

        // Skip 5 packets (send packet with IV 136)
        sender.mEncryptIV[0] = (byte) 135; // encrypt() will increment to 136
        byte[] plain = "Lost packets test".getBytes();
        byte[] enc = sender.encrypt(plain, plain.length);

        receiver.mUiLost = 0;
        byte[] decrypted = receiver.decrypt(enc, enc.length);
        assertNotNull(decrypted);

        // ivbyte = 136, mDecryptIV[0] = 130 -> lost = 136 - 130 - 1 = 5
        assertEquals(5, receiver.mUiLost);
    }
}
