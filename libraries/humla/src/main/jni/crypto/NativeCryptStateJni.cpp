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

#include "CryptStateOCB2.h"

#include <jni.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>

using namespace humla::crypto;

namespace {
std::mutex g_registryMutex;
std::unordered_map<jlong, std::shared_ptr<CryptStateOCB2>> g_registry;
std::atomic<jlong> g_nextHandle{1};

static inline std::shared_ptr<CryptStateOCB2> getCryptState(jlong handle) {
    if (handle <= 0) return nullptr;
    std::lock_guard lock(g_registryMutex);
    auto it = g_registry.find(handle);
    if (it != g_registry.end()) {
        return it->second;
    }
    return nullptr;
}
} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeIsSupported(JNIEnv *, jclass) {
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_net_CryptState_nativeCreate(JNIEnv *, jobject) {
    try {
        auto cs = std::make_shared<CryptStateOCB2>();
        jlong handle = g_nextHandle.fetch_add(1);
        std::lock_guard lock(g_registryMutex);
        g_registry[handle] = std::move(cs);
        return handle;
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_net_CryptState_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<CryptStateOCB2> cs;
    {
        std::lock_guard lock(g_registryMutex);
        auto it = g_registry.find(handle);
        if (it != g_registry.end()) {
            cs = std::move(it->second);
            g_registry.erase(it);
        }
    }
    // Instance is destroyed safely when all in-flight references drop to 0
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeSetKeys(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray rkey,
        jbyteArray eiv,
        jbyteArray div) {
    auto cs = getCryptState(handle);
    if (!cs || !rkey || !eiv || !div) {
        return JNI_FALSE;
    }

    if (env->GetArrayLength(rkey) != AES_KEY_SIZE_BYTES ||
        env->GetArrayLength(eiv) != AES_BLOCK_SIZE ||
        env->GetArrayLength(div) != AES_BLOCK_SIZE) {
        return JNI_FALSE;
    }

    jbyte rkeyBuf[AES_KEY_SIZE_BYTES];
    jbyte eivBuf[AES_BLOCK_SIZE];
    jbyte divBuf[AES_BLOCK_SIZE];

    env->GetByteArrayRegion(rkey, 0, AES_KEY_SIZE_BYTES, rkeyBuf);
    env->GetByteArrayRegion(eiv, 0, AES_BLOCK_SIZE, eivBuf);
    env->GetByteArrayRegion(div, 0, AES_BLOCK_SIZE, divBuf);

    bool ok = cs->setKey(reinterpret_cast<const uint8_t *>(rkeyBuf),
                         reinterpret_cast<const uint8_t *>(eivBuf),
                         reinterpret_cast<const uint8_t *>(divBuf));
    secure_zero(rkeyBuf, sizeof(rkeyBuf));
    secure_zero(eivBuf, sizeof(eivBuf));
    secure_zero(divBuf, sizeof(divBuf));
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeSetDecryptIV(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray div) {
    auto cs = getCryptState(handle);
    if (!cs || !div || env->GetArrayLength(div) != AES_BLOCK_SIZE) {
        return JNI_FALSE;
    }

    jbyte divBuf[AES_BLOCK_SIZE];
    env->GetByteArrayRegion(div, 0, AES_BLOCK_SIZE, divBuf);

    bool ok = cs->setDecryptIV(reinterpret_cast<const uint8_t *>(divBuf));
    secure_zero(divBuf, sizeof(divBuf));
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeSetEncryptIV(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray eiv) {
    auto cs = getCryptState(handle);
    if (!cs || !eiv || env->GetArrayLength(eiv) != AES_BLOCK_SIZE) {
        return JNI_FALSE;
    }

    jbyte eivBuf[AES_BLOCK_SIZE];
    env->GetByteArrayRegion(eiv, 0, AES_BLOCK_SIZE, eivBuf);

    bool ok = cs->setEncryptIV(reinterpret_cast<const uint8_t *>(eivBuf));
    secure_zero(eivBuf, sizeof(eivBuf));
    return ok ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeGetEncryptIV(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray out) {
    auto cs = getCryptState(handle);
    if (!cs || !out || env->GetArrayLength(out) < AES_BLOCK_SIZE) {
        return JNI_FALSE;
    }

    uint8_t iv[AES_BLOCK_SIZE];
    cs->getEncryptIV(iv);
    env->SetByteArrayRegion(out, 0, AES_BLOCK_SIZE, reinterpret_cast<const jbyte *>(iv));
    secure_zero(iv, sizeof(iv));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeGetDecryptIV(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray out) {
    auto cs = getCryptState(handle);
    if (!cs || !out || env->GetArrayLength(out) < AES_BLOCK_SIZE) {
        return JNI_FALSE;
    }

    uint8_t iv[AES_BLOCK_SIZE];
    cs->getDecryptIV(iv);
    env->SetByteArrayRegion(out, 0, AES_BLOCK_SIZE, reinterpret_cast<const jbyte *>(iv));
    secure_zero(iv, sizeof(iv));
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_se_lublin_humla_net_CryptState_nativeEncrypt(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray source,
        jint length) {
    auto cs = getCryptState(handle);
    if (!cs || !source || length < 0) {
        return nullptr;
    }

    jint srcLen = env->GetArrayLength(source);
    if (length > srcLen) {
        return nullptr;
    }

    jbyteArray dst = env->NewByteArray(length + 4);
    if (!dst) {
        return nullptr;
    }

    std::unique_lock lock(cs->getEncryptMutex());

    jbyte *srcPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(source, nullptr));
    if (!srcPtr) {
        return nullptr;
    }

    jbyte *dstPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(dst, nullptr));
    if (!dstPtr) {
        env->ReleasePrimitiveArrayCritical(source, srcPtr, JNI_ABORT);
        return nullptr;
    }

    bool ok = cs->encryptUnlocked(reinterpret_cast<const uint8_t *>(srcPtr),
                                  reinterpret_cast<uint8_t *>(dstPtr),
                                  static_cast<uint32_t>(length));

    env->ReleasePrimitiveArrayCritical(dst, dstPtr, 0);
    env->ReleasePrimitiveArrayCritical(source, srcPtr, JNI_ABORT);
    lock.unlock();

    if (!ok) {
        return nullptr;
    }
    return dst;
}

JNIEXPORT jbyteArray JNICALL
Java_se_lublin_humla_net_CryptState_nativeDecrypt(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray source,
        jint length) {
    auto cs = getCryptState(handle);
    if (!cs || !source || length < 4) {
        return nullptr;
    }

    jint srcLen = env->GetArrayLength(source);
    if (length > srcLen) {
        return nullptr;
    }

    jint plainLength = length - 4;
    jbyteArray dst = env->NewByteArray(plainLength);
    if (!dst) {
        return nullptr;
    }

    std::unique_lock lock(cs->getDecryptMutex());

    jbyte *srcPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(source, nullptr));
    if (!srcPtr) {
        return nullptr;
    }

    jbyte *dstPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(dst, nullptr));
    if (!dstPtr) {
        env->ReleasePrimitiveArrayCritical(source, srcPtr, JNI_ABORT);
        return nullptr;
    }

    bool ok = cs->decryptUnlocked(reinterpret_cast<const uint8_t *>(srcPtr),
                                  reinterpret_cast<uint8_t *>(dstPtr),
                                  static_cast<uint32_t>(length));

    env->ReleasePrimitiveArrayCritical(dst, dstPtr, 0);
    env->ReleasePrimitiveArrayCritical(source, srcPtr, JNI_ABORT);
    lock.unlock();

    if (!ok) {
        return nullptr;
    }
    return dst;
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_net_CryptState_nativeDecryptInPlace(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray buffer,
        jint offset,
        jint cryptedLength) {
    auto cs = getCryptState(handle);
    if (!cs || !buffer || cryptedLength < 4 || offset < 0) {
        return -1;
    }

    jint bufLen = env->GetArrayLength(buffer);
    if (static_cast<int64_t>(offset) + cryptedLength > bufLen) {
        return -1;
    }

    std::unique_lock lock(cs->getDecryptMutex());

    jbyte *bufPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(buffer, nullptr));
    if (!bufPtr) {
        return -1;
    }

    uint8_t *data = reinterpret_cast<uint8_t *>(bufPtr) + offset;
    bool ok = cs->decryptUnlocked(data, data, static_cast<uint32_t>(cryptedLength));

    env->ReleasePrimitiveArrayCritical(buffer, bufPtr, ok ? 0 : JNI_ABORT);
    lock.unlock();

    if (!ok) {
        return -1;
    }
    return cryptedLength - 4;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_net_CryptState_nativeGetStats(
        JNIEnv *env,
        jobject,
        jlong handle,
        jintArray statsOut) {
    auto cs = getCryptState(handle);
    if (!cs || !statsOut || env->GetArrayLength(statsOut) < 4) {
        return;
    }

    jint stats[4];
    stats[0] = cs->getGood();
    stats[1] = cs->getLate();
    stats[2] = cs->getLost();
    stats[3] = cs->getResync();

    env->SetIntArrayRegion(statsOut, 0, 4, stats);
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_net_CryptState_nativeGetLastGoodElapsedUs(
        JNIEnv *,
        jobject,
        jlong handle) {
    auto cs = getCryptState(handle);
    if (!cs) {
        return -1;
    }
    return cs->getLastGoodElapsedUs();
}

} // extern "C"
