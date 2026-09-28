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
#include <android/log.h>
#include <cstdint>
#include <cstring>

#define LOG_TAG "NativeCryptState"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace humla::crypto;

static inline CryptStateOCB2 *getCryptState(jlong handle) {
    return reinterpret_cast<CryptStateOCB2 *>(handle);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeIsSupported(JNIEnv *, jclass) {
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_net_CryptState_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new CryptStateOCB2());
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_net_CryptState_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    CryptStateOCB2 *cs = getCryptState(handle);
    delete cs;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_net_CryptState_nativeGenKey(JNIEnv *, jobject, jlong handle) {
    CryptStateOCB2 *cs = getCryptState(handle);
    if (cs) {
        cs->genKey();
    }
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeSetKeys(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray rkey,
        jbyteArray eiv,
        jbyteArray div) {
    CryptStateOCB2 *cs = getCryptState(handle);
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
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeSetDecryptIV(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray div) {
    CryptStateOCB2 *cs = getCryptState(handle);
    if (!cs || !div || env->GetArrayLength(div) != AES_BLOCK_SIZE) {
        return JNI_FALSE;
    }

    jbyte divBuf[AES_BLOCK_SIZE];
    env->GetByteArrayRegion(div, 0, AES_BLOCK_SIZE, divBuf);

    bool ok = cs->setDecryptIV(reinterpret_cast<const uint8_t *>(divBuf));
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeSetEncryptIV(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray eiv) {
    CryptStateOCB2 *cs = getCryptState(handle);
    if (!cs || !eiv || env->GetArrayLength(eiv) != AES_BLOCK_SIZE) {
        return JNI_FALSE;
    }

    jbyte eivBuf[AES_BLOCK_SIZE];
    env->GetByteArrayRegion(eiv, 0, AES_BLOCK_SIZE, eivBuf);

    bool ok = cs->setEncryptIV(reinterpret_cast<const uint8_t *>(eivBuf));
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeGetEncryptIV(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray out) {
    CryptStateOCB2 *cs = getCryptState(handle);
    if (!cs || !out || env->GetArrayLength(out) < AES_BLOCK_SIZE) {
        return JNI_FALSE;
    }

    uint8_t iv[AES_BLOCK_SIZE];
    cs->getEncryptIV(iv);
    env->SetByteArrayRegion(out, 0, AES_BLOCK_SIZE, reinterpret_cast<const jbyte *>(iv));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_se_lublin_humla_net_CryptState_nativeGetDecryptIV(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray out) {
    CryptStateOCB2 *cs = getCryptState(handle);
    if (!cs || !out || env->GetArrayLength(out) < AES_BLOCK_SIZE) {
        return JNI_FALSE;
    }

    uint8_t iv[AES_BLOCK_SIZE];
    cs->getDecryptIV(iv);
    env->SetByteArrayRegion(out, 0, AES_BLOCK_SIZE, reinterpret_cast<const jbyte *>(iv));
    return JNI_TRUE;
}

JNIEXPORT jbyteArray JNICALL
Java_se_lublin_humla_net_CryptState_nativeEncrypt(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray source,
        jint length) {
    CryptStateOCB2 *cs = getCryptState(handle);
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

    jbyte *srcPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(source, nullptr));
    if (!srcPtr) {
        return nullptr;
    }

    jbyte *dstPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(dst, nullptr));
    if (!dstPtr) {
        env->ReleasePrimitiveArrayCritical(source, srcPtr, JNI_ABORT);
        return nullptr;
    }

    bool ok = cs->encrypt(reinterpret_cast<const uint8_t *>(srcPtr),
                          reinterpret_cast<uint8_t *>(dstPtr),
                          static_cast<uint32_t>(length));

    env->ReleasePrimitiveArrayCritical(dst, dstPtr, 0);
    env->ReleasePrimitiveArrayCritical(source, srcPtr, JNI_ABORT);

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
    CryptStateOCB2 *cs = getCryptState(handle);
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

    jbyte *srcPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(source, nullptr));
    if (!srcPtr) {
        return nullptr;
    }

    jbyte *dstPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(dst, nullptr));
    if (!dstPtr) {
        env->ReleasePrimitiveArrayCritical(source, srcPtr, JNI_ABORT);
        return nullptr;
    }

    bool ok = cs->decrypt(reinterpret_cast<const uint8_t *>(srcPtr),
                          reinterpret_cast<uint8_t *>(dstPtr),
                          static_cast<uint32_t>(length));

    env->ReleasePrimitiveArrayCritical(dst, dstPtr, 0);
    env->ReleasePrimitiveArrayCritical(source, srcPtr, JNI_ABORT);

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
    CryptStateOCB2 *cs = getCryptState(handle);
    if (!cs || !buffer || cryptedLength < 4 || offset < 0) {
        return -1;
    }

    jint bufLen = env->GetArrayLength(buffer);
    if (offset + cryptedLength > bufLen) {
        return -1;
    }

    jbyte *bufPtr = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(buffer, nullptr));
    if (!bufPtr) {
        return -1;
    }

    uint8_t *data = reinterpret_cast<uint8_t *>(bufPtr) + offset;
    bool ok = cs->decrypt(data, data, static_cast<uint32_t>(cryptedLength));

    env->ReleasePrimitiveArrayCritical(buffer, bufPtr, 0);

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
    CryptStateOCB2 *cs = getCryptState(handle);
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
    CryptStateOCB2 *cs = getCryptState(handle);
    if (!cs) {
        return -1;
    }
    return cs->getLastGoodElapsedUs();
}

} // extern "C"
