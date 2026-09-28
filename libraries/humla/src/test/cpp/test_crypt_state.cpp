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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "Aes128.h"
#include "CryptStateOCB2.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>

using namespace humla::crypto;

static void test_aes128_fips197() {
    printf("Testing AES-128 FIPS-197 Appendix C.1 known-answer vectors...\n");

    const uint8_t key[16] = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
    };
    const uint8_t plaintext[16] = {
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
    };
    const uint8_t expected_ciphertext[16] = {
        0x69, 0xc4, 0xe0, 0xd8, 0x6a, 0x7b, 0x04, 0x30,
        0xd8, 0xcd, 0xb7, 0x80, 0x70, 0xb4, 0xc5, 0x5a
    };

    Aes128Key k;
    k.set_key(key);

    uint8_t out[16];
    aes128_encrypt_block(plaintext, out, &k);
    assert(memcmp(out, expected_ciphertext, 16) == 0);

    uint8_t decrypted[16];
    aes128_decrypt_block(out, decrypted, &k);
    assert(memcmp(decrypted, plaintext, 16) == 0);

    // Also verify pure software path explicitly
    aes128_encrypt_sw(plaintext, out, &k);
    assert(memcmp(out, expected_ciphertext, 16) == 0);

    aes128_decrypt_sw(out, decrypted, &k);
    assert(memcmp(decrypted, plaintext, 16) == 0);

    printf("  [PASS] AES-128 FIPS-197 Appendix C.1 (SW and HW SIMD)\n");
}

static void test_ocb2_krovetz_vectors() {
    printf("Testing OCB2 draft-krovetz-ocb-00.txt reference test vectors...\n");

    const uint8_t rawkey[AES_BLOCK_SIZE] = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f
    };

    CryptStateOCB2 cs;
    cs.setKey(rawkey, rawkey, rawkey);
    assert(cs.isValid());

    // Vector 1: Blank tag (0-byte plaintext)
    uint8_t tag[16];
    assert(cs.ocb_encrypt(nullptr, nullptr, 0, rawkey, tag));
    const uint8_t blanktag[AES_BLOCK_SIZE] = {
        0xBF, 0x31, 0x08, 0x13, 0x07, 0x73, 0xAD, 0x5E,
        0xC7, 0x0E, 0xC6, 0x9E, 0x78, 0x75, 0xA7, 0xB0
    };
    assert(memcmp(tag, blanktag, 16) == 0);
    printf("  [PASS] draft-krovetz-ocb-00 Vector 1 (Blank tag)\n");

    // Vector 2: 40-byte plaintext (0x00 through 0x27)
    uint8_t source[40];
    uint8_t crypt[40];
    for (uint8_t i = 0; i < 40; i++) source[i] = i;

    assert(cs.ocb_encrypt(source, crypt, 40, rawkey, tag));
    const uint8_t longtag[AES_BLOCK_SIZE] = {
        0x9D, 0xB0, 0xCD, 0xF8, 0x80, 0xF7, 0x3E, 0x3E,
        0x10, 0xD4, 0xEB, 0x32, 0x17, 0x76, 0x66, 0x88
    };
    const uint8_t crypted[40] = {
        0xF7, 0x5D, 0x6B, 0xC8, 0xB4, 0xDC, 0x8D, 0x66, 0xB8, 0x36,
        0xA2, 0xB0, 0x8B, 0x32, 0xA6, 0x36, 0x9F, 0x1C, 0xD3, 0xC5,
        0x22, 0x8D, 0x79, 0xFD, 0x6C, 0x26, 0x7F, 0x5F, 0x6A, 0xA7,
        0xB2, 0x31, 0xC7, 0xDF, 0xB9, 0xD5, 0x99, 0x51, 0xAE, 0x9C
    };
    assert(memcmp(tag, longtag, 16) == 0);
    assert(memcmp(crypt, crypted, 40) == 0);
    printf("  [PASS] draft-krovetz-ocb-00 Vector 2 (40-byte plaintext & tag)\n");
}

static void test_authcrypt_roundtrip_and_inplace() {
    printf("Testing authcrypt roundtrip and in-place encryption/decryption (0..128 bytes)...\n");

    const uint8_t rawkey[AES_BLOCK_SIZE] = {
        0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae, 0xd2, 0xa6,
        0xab, 0xf7, 0x15, 0x88, 0x09, 0xcf, 0x4f, 0x3c
    };

    CryptStateOCB2 cs;
    cs.setKey(rawkey, rawkey, rawkey);

    for (uint32_t len = 0; len <= 128; len++) {
        const uint8_t nonce[16] = {
            0xff, 0xee, 0xdd, 0xcc, 0xbb, 0xaa, 0x99, 0x88,
            0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00
        };
        std::vector<uint8_t> src(len);
        for (uint32_t i = 0; i < len; i++) src[i] = static_cast<uint8_t>(i + 1);

        uint8_t enctag[16], dectag[16];
        std::vector<uint8_t> encrypted(len), decrypted(len);

        const uint8_t *src_ptr = len > 0 ? src.data() : nullptr;
        uint8_t *enc_ptr = len > 0 ? encrypted.data() : nullptr;
        uint8_t *dec_ptr = len > 0 ? decrypted.data() : nullptr;

        assert(cs.ocb_encrypt(src_ptr, enc_ptr, len, nonce, enctag));
        assert(cs.ocb_decrypt(enc_ptr, dec_ptr, len, nonce, dectag));

        assert(memcmp(enctag, dectag, 16) == 0);
        if (len > 0) {
            assert(memcmp(src.data(), decrypted.data(), len) == 0);
        }

        // In-place verification: encrypted memory buffer equals plain buffer
        if (len > 0) {
            std::vector<uint8_t> in_place = src;
            assert(cs.ocb_encrypt(in_place.data(), in_place.data(), len, nonce, enctag));
            assert(cs.ocb_decrypt(in_place.data(), in_place.data(), len, nonce, dectag));
            assert(memcmp(src.data(), in_place.data(), len) == 0);
        }
    }
    printf("  [PASS] Authcrypt 0..128 bytes roundtrip and in-place verification\n");
}

static void test_xex_star_attack_mitigation() {
    printf("Testing IACR ePrint 2019/311 (XEX* attack) countermeasure...\n");

    const uint8_t rawkey[AES_BLOCK_SIZE] = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
    };

    CryptStateOCB2 cs;
    cs.setKey(rawkey, rawkey, rawkey);

    const uint8_t nonce[16] = {
        0xff, 0xee, 0xdd, 0xcc, 0xbb, 0xaa, 0x99, 0x88,
        0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 0x00
    };
    std::vector<uint8_t> src(2 * AES_BLOCK_SIZE, 0);
    src[AES_BLOCK_SIZE - 1] = AES_BLOCK_SIZE * 8;
    memset(src.data() + AES_BLOCK_SIZE, 42, AES_BLOCK_SIZE);

    uint8_t enctag[16], dectag[16];
    std::vector<uint8_t> encrypted(2 * AES_BLOCK_SIZE);
    std::vector<uint8_t> decrypted(2 * AES_BLOCK_SIZE);

    // Without bit flip, encryption detects attack structure and fails
    const bool failed_encrypt = !cs.ocb_encrypt(src.data(), encrypted.data(), 2 * AES_BLOCK_SIZE, nonce, enctag, false);

    // Perform the attack forgery
    encrypted[AES_BLOCK_SIZE - 1] ^= AES_BLOCK_SIZE * 8;
    for (int i = 0; i < AES_BLOCK_SIZE; ++i)
        enctag[i] = src[AES_BLOCK_SIZE + i] ^ encrypted[AES_BLOCK_SIZE + i];

    const bool failed_decrypt = !cs.ocb_decrypt(encrypted.data(), decrypted.data(), 1 * AES_BLOCK_SIZE, nonce, dectag);

    // Verify forged tag matches the forged dectag
    for (int i = 0; i < AES_BLOCK_SIZE; ++i) {
        assert(enctag[i] == dectag[i]);
    }

    assert(failed_encrypt);
    assert(failed_decrypt);

    // Now test with default bit flip: digital silence is safely modified to prevent attack
    assert(cs.ocb_encrypt(src.data(), encrypted.data(), 2 * AES_BLOCK_SIZE, nonce, enctag));
    assert(cs.ocb_decrypt(encrypted.data(), decrypted.data(), 2 * AES_BLOCK_SIZE, nonce, dectag));

    for (int i = 0; i < AES_BLOCK_SIZE; ++i) {
        assert(enctag[i] == dectag[i]);
    }

    assert(src[0] == 0);
    assert(decrypted[0] == 1);

    printf("  [PASS] XEX* attack detected and prevented on sender and receiver\n");
}

static void test_iv_recovery_and_replay_detection() {
    printf("Testing IV recovery, rollover, and replay detection...\n");

    const uint8_t rawkey[AES_BLOCK_SIZE] = {
        0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
        0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0, 0xf0, 0x00
    };

    CryptStateOCB2 sender, receiver;
    sender.setKey(rawkey, rawkey, rawkey);
    receiver.setKey(rawkey, rawkey, rawkey);

    const uint8_t secret[10] = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'};
    uint8_t packet[14];
    uint8_t decrypted[10];

    assert(sender.encrypt(secret, packet, 10));
    assert(receiver.decrypt(packet, decrypted, 14));
    assert(memcmp(secret, decrypted, 10) == 0);
    assert(receiver.getGood() == 1);
    assert(receiver.getLastGoodElapsedUs() >= 0);

    // Replay of same packet must be rejected
    assert(!receiver.decrypt(packet, decrypted, 14));
    assert(receiver.getGood() == 1);

    // Test in-place packet decrypt where source == dst
    uint8_t inplace_packet[14];
    assert(sender.encrypt(secret, inplace_packet, 10));
    assert(receiver.decrypt(inplace_packet, inplace_packet, 14));
    assert(memcmp(secret, inplace_packet, 10) == 0);

    // Skip 5 packets (lost packets)
    for (int i = 0; i < 5; i++) {
        assert(sender.encrypt(secret, packet, 10));
    }
    assert(receiver.decrypt(packet, decrypted, 14));
    assert(memcmp(secret, decrypted, 10) == 0);
    assert(receiver.getLost() == 4);

    // Out-of-order recovery (burst of packets arriving in reverse order)
    uint8_t burst[35][14];
    for (int i = 0; i < 35; i++) {
        assert(sender.encrypt(secret, burst[i], 10));
    }
    for (int i = 0; i < 25; i++) {
        assert(receiver.decrypt(burst[34 - i], decrypted, 14));
        assert(memcmp(secret, decrypted, 10) == 0);
    }

    printf("  [PASS] IV recovery, packet loss tracking, and replay detection\n");
}

static void test_wraparound_and_replay_stress() {
    printf("Testing 256+ packet wraparound, unrecoverable drift, and 512-packet replay...\n");

    CryptStateOCB2 enc, dec;
    enc.genKey();

    uint8_t rawkey[AES_BLOCK_SIZE];
    uint8_t eiv[AES_BLOCK_SIZE];
    uint8_t div[AES_BLOCK_SIZE];
    enc.getRawKey(rawkey);
    enc.getEncryptIV(eiv);
    enc.getDecryptIV(div);

    // dec uses enc's encrypt_iv as its decrypt_iv, and vice versa
    dec.setKey(rawkey, div, eiv);

    const uint8_t secret[10] = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 0};
    uint8_t crypted[512][14];
    uint8_t decr[10];

    // Extensive replay test: encrypt 512 packets
    for (int i = 0; i < 512; i++) {
        assert(enc.encrypt(secret, crypted[i], 10));
    }
    // Decrypt all 512 packets in order
    for (int i = 0; i < 512; i++) {
        assert(dec.decrypt(crypted[i], decr, 14));
        assert(memcmp(secret, decr, 10) == 0);
    }
    // Replay attack: attempting to replay all 512 packets must be rejected
    for (int i = 0; i < 512; i++) {
        assert(!dec.decrypt(crypted[i], decr, 14));
    }

    // Wraparound test: 128 cycles of 15 packets lost, recovering correctly
    for (int i = 0; i < 128; i++) {
        uint8_t pkt[14];
        for (int j = 0; j < 15; j++) {
            assert(enc.encrypt(secret, pkt, 10));
        }
        assert(dec.decrypt(pkt, decr, 14));
        assert(memcmp(secret, decr, 10) == 0);
    }

    uint8_t enc_iv[AES_BLOCK_SIZE], dec_iv[AES_BLOCK_SIZE];
    enc.getEncryptIV(enc_iv);
    dec.getDecryptIV(dec_iv);
    assert(memcmp(enc_iv, dec_iv, AES_BLOCK_SIZE) == 0);

    // Wrap too far (> 256 packets skipped without resync)
    uint8_t drift_pkt[14];
    for (int i = 0; i < 257; i++) {
        assert(enc.encrypt(secret, drift_pkt, 10));
    }
    // Must fail to decrypt due to excessive IV drift
    assert(!dec.decrypt(drift_pkt, decr, 14));

    // Resynchronize decrypt IV to match sender's encrypt IV
    enc.getEncryptIV(enc_iv);
    assert(dec.setDecryptIV(enc_iv));

    // After resync, next packet decrypts successfully
    assert(enc.encrypt(secret, drift_pkt, 10));
    assert(dec.decrypt(drift_pkt, decr, 14));
    assert(memcmp(secret, decr, 10) == 0);

    printf("  [PASS] 256+ packet wraparound, drift detection, and 512-packet replay\n");
}

static void test_concurrency_stress() {
    printf("Testing concurrent full-duplex multi-threaded encrypt and decrypt on single instance...\n");

    const uint8_t key1[AES_BLOCK_SIZE] = {
        0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc,
        0xdd, 0xee, 0xff, 0x00, 0x11, 0x22, 0x33, 0x44
    };
    const uint8_t iv1[AES_BLOCK_SIZE] = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
    };
    const uint8_t iv2[AES_BLOCK_SIZE] = {
        0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8,
        0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf, 0xb0
    };

    // 'local' represents the full-duplex client.
    // Outbound uses key1, encrypt_iv = iv1.
    // Inbound uses key1, decrypt_iv = iv2.
    CryptStateOCB2 local;
    local.setKey(key1, iv1, iv2);

    // 'remote' is the peer talking to 'local'.
    // remote outbound uses encrypt_iv = iv2, decrypt_iv = iv1.
    CryptStateOCB2 remote;
    remote.setKey(key1, iv2, iv1);

    constexpr int NUM_PACKETS = 1000;
    constexpr int PAYLOAD_LEN = 48;

    // Pre-encrypt 1000 packets from remote to feed into local.decrypt concurrently
    std::vector<std::vector<uint8_t>> incoming_packets(NUM_PACKETS, std::vector<uint8_t>(PAYLOAD_LEN + 4));
    for (int i = 0; i < NUM_PACKETS; i++) {
        uint8_t plain[PAYLOAD_LEN];
        memset(plain, static_cast<uint8_t>(i & 0xff), PAYLOAD_LEN);
        bool ok = remote.encrypt(plain, incoming_packets[i].data(), PAYLOAD_LEN);
        assert(ok);
    }

    std::vector<std::vector<uint8_t>> outbound_packets(NUM_PACKETS, std::vector<uint8_t>(PAYLOAD_LEN + 4));
    std::atomic<bool> start_gate{false};

    // Thread 1: Outbound transmit - encrypts on local concurrently
    std::thread sender([&]() {
        while (!start_gate.load()) {
            std::this_thread::yield();
        }
        uint8_t mic_sample[PAYLOAD_LEN];
        for (int i = 0; i < NUM_PACKETS; i++) {
            memset(mic_sample, static_cast<uint8_t>(i ^ 0x5a), PAYLOAD_LEN);
            bool ok = local.encrypt(mic_sample, outbound_packets[i].data(), PAYLOAD_LEN);
            assert(ok);
        }
    });

    // Thread 2: Inbound receive - decrypts on local concurrently
    std::thread receiver([&]() {
        while (!start_gate.load()) {
            std::this_thread::yield();
        }
        uint8_t recv_buf[PAYLOAD_LEN];
        for (int i = 0; i < NUM_PACKETS; i++) {
            bool ok = local.decrypt(incoming_packets[i].data(), recv_buf, incoming_packets[i].size());
            assert(ok);
            assert(recv_buf[0] == static_cast<uint8_t>(i & 0xff));
        }
    });

    // Release both threads simultaneously
    start_gate.store(true);

    sender.join();
    receiver.join();

    assert(local.getGood() == NUM_PACKETS);

    // Verify remote peer can decrypt all outbound packets produced concurrently by local
    for (int i = 0; i < NUM_PACKETS; i++) {
        uint8_t decoded[PAYLOAD_LEN];
        bool ok = remote.decrypt(outbound_packets[i].data(), decoded, outbound_packets[i].size());
        assert(ok);
        assert(decoded[0] == static_cast<uint8_t>(i ^ 0x5a));
    }
    assert(remote.getGood() == NUM_PACKETS);

    printf("  [PASS] Concurrent full-duplex stress on single CryptStateOCB2 instance (%d packets)\n", NUM_PACKETS);
}

int main() {
    printf("=== Starting Native CryptStateOCB2 Test Suite ===\n");
    test_aes128_fips197();
    test_ocb2_krovetz_vectors();
    test_authcrypt_roundtrip_and_inplace();
    test_xex_star_attack_mitigation();
    test_iv_recovery_and_replay_detection();
    test_wraparound_and_replay_stress();
    test_concurrency_stress();
    printf("=== All CryptStateOCB2 Native Tests Passed! ===\n");
    return 0;
}
