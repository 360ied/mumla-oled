// Copyright (C) 2026 Brian Zhu
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

// Test-only JNI hook for the crash recorder's native capture path.
// abort() rather than a null deref: -O3 makes a null write fragile UB,
// and JNIEXPORT already overrides -fvisibility=hidden.

#include <cstdlib>
#include <jni.h>

extern "C" {

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_NativeCrashTest_nativeCrash(JNIEnv*, jclass) {
    abort();
}

}  // extern "C"
