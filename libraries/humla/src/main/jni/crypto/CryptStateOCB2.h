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

#ifndef HUMLA_CRYPTO_CRYPTSTATEOCB2_H
#define HUMLA_CRYPTO_CRYPTSTATEOCB2_H

#include "Aes128.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>

namespace humla::crypto {

constexpr int AES_BLOCK_SIZE = 16;
constexpr int AES_KEY_SIZE_BYTES = 16;

class CryptStateOCB2 {
public:
    CryptStateOCB2();
    ~CryptStateOCB2() = default;

    bool isValid() const;
    void genKey();
    bool setKey(const uint8_t *rkey, const uint8_t *eiv, const uint8_t *div);
    bool setDecryptIV(const uint8_t *div);
    bool setEncryptIV(const uint8_t *eiv);

    void getEncryptIV(uint8_t out[16]) const;
    void getDecryptIV(uint8_t out[16]) const;
    void getRawKey(uint8_t out[16]) const;

    bool encrypt(const uint8_t *source, uint8_t *dst, uint32_t plain_length);
    bool decrypt(const uint8_t *source, uint8_t *dst, uint32_t crypted_length);

    bool ocb_encrypt(const uint8_t *plain, uint8_t *encrypted, uint32_t len, const uint8_t *nonce,
                     uint8_t *tag, bool modifyPlainOnXEXStarAttack = true);
    bool ocb_decrypt(const uint8_t *encrypted, uint8_t *plain, uint32_t len, const uint8_t *nonce,
                     uint8_t *tag);

    int32_t getGood() const;
    int32_t getLate() const;
    int32_t getLost() const;
    int32_t getResync() const;

    int64_t getLastGoodElapsedUs() const;

private:
    uint8_t raw_key[AES_KEY_SIZE_BYTES];
    uint8_t encrypt_iv[AES_BLOCK_SIZE];
    uint8_t decrypt_iv[AES_BLOCK_SIZE];
    uint8_t decrypt_history[0x100];
    Aes128Key aesKey;

    mutable std::mutex m_encryptMutex;
    mutable std::mutex m_decryptMutex;

    std::atomic<int32_t> m_statGood{0};
    std::atomic<int32_t> m_statLate{0};
    std::atomic<int32_t> m_statLost{0};
    std::atomic<int32_t> m_statResync{0};

    std::chrono::steady_clock::time_point m_lastGoodTime;
    std::atomic<bool> m_hasLastGood{false};
    bool bInit = false;
};

} // namespace humla::crypto

#endif // HUMLA_CRYPTO_CRYPTSTATEOCB2_H
