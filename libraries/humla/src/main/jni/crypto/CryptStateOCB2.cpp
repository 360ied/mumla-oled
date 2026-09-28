/*
 * Copyright (C) 2026 Brian Zhu
 *
 * Based on CryptStateOCB2 from the Mumble project (https://www.mumble.info)
 * Copyright The Mumble Developers. All rights reserved.
 * OCB algorithm design by Phillip Rogaway (dedicated to the public domain).
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

#include "CryptStateOCB2.h"

#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <random>
#include <unistd.h>

namespace humla::crypto {

#if defined(__LP64__)
#define BLOCKSIZE 2
#define SHIFTBITS 63
typedef uint64_t __attribute__((aligned(1))) subblock;
#define SWAPPED(x) __builtin_bswap64(x)
#else
#define BLOCKSIZE 4
#define SHIFTBITS 31
typedef uint32_t __attribute__((aligned(1))) subblock;
#define SWAPPED(x) __builtin_bswap32(x)
#endif

typedef subblock keyblock[BLOCKSIZE];

static inline void XOR(subblock *dst, const subblock *a, const subblock *b) {
    for (int i = 0; i < BLOCKSIZE; i++) {
        dst[i] = a[i] ^ b[i];
    }
}

static inline void S2(subblock *block) {
    subblock carry = SWAPPED(block[0]) >> SHIFTBITS;
    for (int i = 0; i < BLOCKSIZE - 1; i++)
        block[i] = SWAPPED((SWAPPED(block[i]) << 1) | (SWAPPED(block[i + 1]) >> SHIFTBITS));
    block[BLOCKSIZE - 1] = SWAPPED((SWAPPED(block[BLOCKSIZE - 1]) << 1) ^ (carry * 0x87));
}

static inline void S3(subblock *block) {
    subblock carry = SWAPPED(block[0]) >> SHIFTBITS;
    for (int i = 0; i < BLOCKSIZE - 1; i++)
        block[i] ^= SWAPPED((SWAPPED(block[i]) << 1) | (SWAPPED(block[i + 1]) >> SHIFTBITS));
    block[BLOCKSIZE - 1] ^= SWAPPED((SWAPPED(block[BLOCKSIZE - 1]) << 1) ^ (carry * 0x87));
}

static inline void ZERO(keyblock &block) {
    for (int i = 0; i < BLOCKSIZE; i++)
        block[i] = 0;
}

CryptStateOCB2::CryptStateOCB2() {
    memset(raw_key, 0, AES_KEY_SIZE_BYTES);
    memset(encrypt_iv, 0, AES_BLOCK_SIZE);
    memset(decrypt_iv, 0, AES_BLOCK_SIZE);
    memset(decrypt_history, 0, 0x100);
}

bool CryptStateOCB2::isValid() const {
    return bInit;
}

void CryptStateOCB2::genKey() {
    std::scoped_lock lock(m_encryptMutex, m_decryptMutex);
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    bool readOk = false;
    if (fd >= 0) {
        ssize_t r1 = read(fd, raw_key, AES_KEY_SIZE_BYTES);
        ssize_t r2 = read(fd, encrypt_iv, AES_BLOCK_SIZE);
        ssize_t r3 = read(fd, decrypt_iv, AES_BLOCK_SIZE);
        close(fd);
        if (r1 == AES_KEY_SIZE_BYTES && r2 == AES_BLOCK_SIZE && r3 == AES_BLOCK_SIZE) {
            readOk = true;
        }
    }
    if (!readOk) {
        std::random_device rd;
        for (int i = 0; i < AES_KEY_SIZE_BYTES; i++) raw_key[i] = static_cast<uint8_t>(rd());
        for (int i = 0; i < AES_BLOCK_SIZE; i++) encrypt_iv[i] = static_cast<uint8_t>(rd());
        for (int i = 0; i < AES_BLOCK_SIZE; i++) decrypt_iv[i] = static_cast<uint8_t>(rd());
    }
    memset(decrypt_history, 0, 0x100);
    m_hasLastGood.store(false);
    m_statGood.store(0);
    m_statLate.store(0);
    m_statLost.store(0);
    aesKey.set_key(raw_key);
    bInit = true;
}

bool CryptStateOCB2::setKey(const uint8_t *rkey, const uint8_t *eiv, const uint8_t *div) {
    if (!rkey || !eiv || !div) return false;
    std::scoped_lock lock(m_encryptMutex, m_decryptMutex);
    memcpy(raw_key, rkey, AES_KEY_SIZE_BYTES);
    memcpy(encrypt_iv, eiv, AES_BLOCK_SIZE);
    memcpy(decrypt_iv, div, AES_BLOCK_SIZE);
    memset(decrypt_history, 0, 0x100);
    m_hasLastGood.store(false);
    m_statGood.store(0);
    m_statLate.store(0);
    m_statLost.store(0);
    aesKey.set_key(raw_key);
    bInit = true;
    return true;
}

bool CryptStateOCB2::setDecryptIV(const uint8_t *div) {
    if (!div) return false;
    std::lock_guard lock(m_decryptMutex);
    memcpy(decrypt_iv, div, AES_BLOCK_SIZE);
    memset(decrypt_history, 0, 0x100);
    return true;
}

bool CryptStateOCB2::setEncryptIV(const uint8_t *eiv) {
    if (!eiv) return false;
    std::lock_guard lock(m_encryptMutex);
    memcpy(encrypt_iv, eiv, AES_BLOCK_SIZE);
    return true;
}

void CryptStateOCB2::getEncryptIV(uint8_t out[16]) const {
    std::lock_guard lock(m_encryptMutex);
    memcpy(out, encrypt_iv, 16);
}

void CryptStateOCB2::getDecryptIV(uint8_t out[16]) const {
    std::lock_guard lock(m_decryptMutex);
    memcpy(out, decrypt_iv, 16);
}

void CryptStateOCB2::getRawKey(uint8_t out[16]) const {
    std::scoped_lock lock(m_encryptMutex, m_decryptMutex);
    memcpy(out, raw_key, 16);
}

bool CryptStateOCB2::encrypt(const uint8_t *source, uint8_t *dst, uint32_t plain_length) {
    std::lock_guard lock(m_encryptMutex);
    return encryptUnlocked(source, dst, plain_length);
}

bool CryptStateOCB2::encryptUnlocked(const uint8_t *source, uint8_t *dst, uint32_t plain_length) {
    if (!bInit.load() || !source || !dst) return false;

    // Reject invalid overlapping buffers
    uintptr_t s = reinterpret_cast<uintptr_t>(source);
    uintptr_t d = reinterpret_cast<uintptr_t>(dst);
    if (s < d + plain_length + 4 && d < s + plain_length) {
        return false;
    }

    uint8_t tag[AES_BLOCK_SIZE];

    for (int i = 0; i < AES_BLOCK_SIZE; i++) {
        if (++encrypt_iv[i])
            break;
    }

    if (!ocb_encrypt(source, dst + 4, plain_length, encrypt_iv, tag)) {
        return false;
    }

    dst[0] = encrypt_iv[0];
    dst[1] = tag[0];
    dst[2] = tag[1];
    dst[3] = tag[2];
    return true;
}

bool CryptStateOCB2::decrypt(const uint8_t *source, uint8_t *dst, uint32_t crypted_length) {
    std::lock_guard lock(m_decryptMutex);
    return decryptUnlocked(source, dst, crypted_length);
}

bool CryptStateOCB2::decryptUnlocked(const uint8_t *source, uint8_t *dst, uint32_t crypted_length) {
    if (crypted_length < 4 || !bInit.load() || !source || !dst) return false;
    uint32_t plain_length = crypted_length - 4;

    // Reject partially overlapping buffers (exact in-place source == dst is permitted)
    uintptr_t s = reinterpret_cast<uintptr_t>(source);
    uintptr_t d = reinterpret_cast<uintptr_t>(dst);
    if (source != dst && s < d + plain_length && d < s + crypted_length) {
        return false;
    }

    uint8_t saveiv[AES_BLOCK_SIZE];
    uint8_t ivbyte = source[0];
    bool restore = false;
    uint8_t tag[AES_BLOCK_SIZE];

    int lost = 0;
    int late = 0;

    memcpy(saveiv, decrypt_iv, AES_BLOCK_SIZE);

    if (((decrypt_iv[0] + 1) & 0xFF) == ivbyte) {
        // In order as expected
        if (ivbyte > decrypt_iv[0]) {
            decrypt_iv[0] = ivbyte;
        } else if (ivbyte < decrypt_iv[0]) {
            decrypt_iv[0] = ivbyte;
            for (int i = 1; i < AES_BLOCK_SIZE; i++)
                if (++decrypt_iv[i])
                    break;
        } else {
            return false;
        }
    } else {
        // Out of order or repeat
        int diff = ivbyte - decrypt_iv[0];
        if (diff > 128)
            diff = diff - 256;
        else if (diff < -128)
            diff = diff + 256;

        if ((ivbyte < decrypt_iv[0]) && (diff > -30) && (diff < 0)) {
            // Late packet, but no wraparound
            late = 1;
            lost = -1;
            decrypt_iv[0] = ivbyte;
            restore = true;
        } else if ((ivbyte > decrypt_iv[0]) && (diff > -30) && (diff < 0)) {
            // Rollover: was 0x02, incoming 0xFF from previous round
            late = 1;
            lost = -1;
            decrypt_iv[0] = ivbyte;
            for (int i = 1; i < AES_BLOCK_SIZE; i++)
                if (decrypt_iv[i]--)
                    break;
            restore = true;
        } else if ((ivbyte > decrypt_iv[0]) && (diff > 0)) {
            lost = ivbyte - decrypt_iv[0] - 1;
            decrypt_iv[0] = ivbyte;
        } else if ((ivbyte < decrypt_iv[0]) && (diff > 0)) {
            lost = 256 - decrypt_iv[0] + ivbyte - 1;
            decrypt_iv[0] = ivbyte;
            for (int i = 1; i < AES_BLOCK_SIZE; i++)
                if (++decrypt_iv[i])
                    break;
        } else {
            return false;
        }

        if (decrypt_history[decrypt_iv[0]] == decrypt_iv[1]) {
            memcpy(decrypt_iv, saveiv, AES_BLOCK_SIZE);
            return false;
        }
    }

    bool ocb_success;
    if (source == dst) {
        ocb_success = ocb_decrypt(source + 4, dst + 4, plain_length, decrypt_iv, tag);
        if (!ocb_success || memcmp(tag, source + 1, 3) != 0) {
            memcpy(decrypt_iv, saveiv, AES_BLOCK_SIZE);
            return false;
        }
        memmove(dst, source + 4, plain_length);
    } else {
        ocb_success = ocb_decrypt(source + 4, dst, plain_length, decrypt_iv, tag);
        if (!ocb_success || memcmp(tag, source + 1, 3) != 0) {
            memcpy(decrypt_iv, saveiv, AES_BLOCK_SIZE);
            return false;
        }
    }

    decrypt_history[decrypt_iv[0]] = decrypt_iv[1];

    if (restore)
        memcpy(decrypt_iv, saveiv, AES_BLOCK_SIZE);

    m_statGood++;
    if (late > 0) {
        m_statLate += late;
    }

    if (lost > 0) {
        m_statLost += lost;
    } else if (static_cast<int>(m_statLost.load()) >= std::abs(lost)) {
        m_statLost -= std::abs(lost);
    }

    m_lastGoodTime = std::chrono::steady_clock::now();
    m_hasLastGood = true;

    return true;
}

bool CryptStateOCB2::ocb_encrypt(const uint8_t *plain, uint8_t *encrypted, uint32_t len, const uint8_t *nonce,
                                 uint8_t *tag, bool modifyPlainOnXEXStarAttack) {
    keyblock checksum, delta, tmp, pad;
    bool success = true;

    aes128_encrypt_block(nonce, reinterpret_cast<uint8_t *>(delta), &aesKey);
    ZERO(checksum);

    while (len > AES_BLOCK_SIZE) {
        bool flipABit = false;
        if (len - AES_BLOCK_SIZE <= AES_BLOCK_SIZE) {
            uint8_t sum = 0;
            for (int i = 0; i < AES_BLOCK_SIZE - 1; ++i) {
                sum |= plain[i];
            }
            if (sum == 0) {
                if (modifyPlainOnXEXStarAttack) {
                    flipABit = true;
                } else {
                    success = false;
                }
            }
        }

        S2(delta);
        XOR(tmp, delta, reinterpret_cast<const subblock *>(plain));
        XOR(checksum, checksum, reinterpret_cast<const subblock *>(plain));
        if (flipABit) {
            *reinterpret_cast<uint8_t *>(tmp) ^= 1;
            *reinterpret_cast<uint8_t *>(checksum) ^= 1;
        }

        aes128_encrypt_block(reinterpret_cast<const uint8_t *>(tmp),
                             reinterpret_cast<uint8_t *>(tmp), &aesKey);
        XOR(reinterpret_cast<subblock *>(encrypted), delta, tmp);

        len -= AES_BLOCK_SIZE;
        plain += AES_BLOCK_SIZE;
        encrypted += AES_BLOCK_SIZE;
    }

    S2(delta);
    ZERO(tmp);
    tmp[BLOCKSIZE - 1] = SWAPPED(static_cast<subblock>(len * 8));
    XOR(tmp, tmp, delta);
    aes128_encrypt_block(reinterpret_cast<const uint8_t *>(tmp),
                         reinterpret_cast<uint8_t *>(pad), &aesKey);
    if (len > 0) {
        memcpy(tmp, plain, len);
    }
    memcpy(reinterpret_cast<uint8_t *>(tmp) + len,
           reinterpret_cast<const uint8_t *>(pad) + len, AES_BLOCK_SIZE - len);
    XOR(checksum, checksum, tmp);
    XOR(tmp, pad, tmp);
    if (len > 0) {
        memcpy(encrypted, tmp, len);
    }

    S3(delta);
    XOR(tmp, delta, checksum);
    aes128_encrypt_block(reinterpret_cast<const uint8_t *>(tmp), tag, &aesKey);

    return success;
}

bool CryptStateOCB2::ocb_decrypt(const uint8_t *encrypted, uint8_t *plain, uint32_t len, const uint8_t *nonce,
                                 uint8_t *tag) {
    keyblock checksum, delta, tmp, pad;
    bool success = true;

    aes128_encrypt_block(nonce, reinterpret_cast<uint8_t *>(delta), &aesKey);
    ZERO(checksum);

    while (len > AES_BLOCK_SIZE) {
        S2(delta);
        XOR(tmp, delta, reinterpret_cast<const subblock *>(encrypted));
        aes128_decrypt_block(reinterpret_cast<const uint8_t *>(tmp),
                             reinterpret_cast<uint8_t *>(tmp), &aesKey);
        XOR(reinterpret_cast<subblock *>(plain), delta, tmp);
        XOR(checksum, checksum, reinterpret_cast<const subblock *>(plain));
        len -= AES_BLOCK_SIZE;
        plain += AES_BLOCK_SIZE;
        encrypted += AES_BLOCK_SIZE;
    }

    S2(delta);
    ZERO(tmp);
    tmp[BLOCKSIZE - 1] = SWAPPED(static_cast<subblock>(len * 8));
    XOR(tmp, tmp, delta);
    aes128_encrypt_block(reinterpret_cast<const uint8_t *>(tmp),
                         reinterpret_cast<uint8_t *>(pad), &aesKey);
    memset(tmp, 0, AES_BLOCK_SIZE);
    if (len > 0) {
        memcpy(tmp, encrypted, len);
    }
    XOR(tmp, tmp, pad);
    XOR(checksum, checksum, tmp);
    if (len > 0) {
        memcpy(plain, tmp, len);
    }

    // Counter-cryptanalysis ePrint 2019/311
    if (memcmp(tmp, delta, AES_BLOCK_SIZE - 1) == 0) {
        success = false;
    }

    S3(delta);
    XOR(tmp, delta, checksum);
    aes128_encrypt_block(reinterpret_cast<const uint8_t *>(tmp), tag, &aesKey);

    return success;
}

int32_t CryptStateOCB2::getGood() const { return m_statGood.load(); }
int32_t CryptStateOCB2::getLate() const { return m_statLate.load(); }
int32_t CryptStateOCB2::getLost() const { return m_statLost.load(); }
int32_t CryptStateOCB2::getResync() const { return m_statResync.load(); }

int64_t CryptStateOCB2::getLastGoodElapsedUs() const {
    if (!m_hasLastGood.load()) return -1;
    std::lock_guard lock(m_decryptMutex);
    if (!m_hasLastGood.load()) return -1;
    auto now = std::chrono::steady_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(now - m_lastGoodTime).count();
}

} // namespace humla::crypto
