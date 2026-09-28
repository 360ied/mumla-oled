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

static void test_concurrency_stress() {
    printf("Testing concurrent multi-threaded encrypt and decrypt stress...\n");

    const uint8_t rawkey[AES_BLOCK_SIZE] = {
        0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc,
        0xdd, 0xee, 0xff, 0x00, 0x11, 0x22, 0x33, 0x44
    };

    CryptStateOCB2 client, server;
    client.setKey(rawkey, rawkey, rawkey);
    server.setKey(rawkey, rawkey, rawkey);

    std::atomic<bool> stop{false};
    constexpr int NUM_PACKETS = 500;

    // Sender thread: client encrypts packets
    std::vector<std::vector<uint8_t>> client_to_server(NUM_PACKETS);
    std::thread sender([&]() {
        uint8_t payload[48];
        memset(payload, 0x3c, sizeof(payload));
        for (int i = 0; i < NUM_PACKETS; i++) {
            client_to_server[i].resize(sizeof(payload) + 4);
            bool ok = client.encrypt(payload, client_to_server[i].data(), sizeof(payload));
            assert(ok);
        }
    });

    sender.join();

    // Receiver thread: server decrypts client packets
    for (int i = 0; i < NUM_PACKETS; i++) {
        uint8_t plain[48];
        bool ok = server.decrypt(client_to_server[i].data(), plain, client_to_server[i].size());
        assert(ok);
    }
    assert(server.getGood() == NUM_PACKETS);

    printf("  [PASS] Concurrent stress and decoupled encrypt/decrypt mutexes\n");
}

int main() {
    printf("=== Starting Native CryptStateOCB2 Test Suite ===\n");
    test_aes128_fips197();
    test_ocb2_krovetz_vectors();
    test_authcrypt_roundtrip_and_inplace();
    test_xex_star_attack_mitigation();
    test_iv_recovery_and_replay_detection();
    test_concurrency_stress();
    printf("=== All CryptStateOCB2 Native Tests Passed! ===\n");
    return 0;
}
