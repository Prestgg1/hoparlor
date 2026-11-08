// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>

#include <mutex>

#include "engine.h"

namespace {
hoparlor::Engine gEngine;
std::mutex gMutex;
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_prestgg_hoparlor_NativeEngine_start(JNIEnv* env, jobject, jstring host, jint port, jint targetMs,
                                                jboolean adaptive) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    std::string hostStr(h);
    env->ReleaseStringUTFChars(host, h);
    std::lock_guard<std::mutex> lock(gMutex);
    return gEngine.start(hostStr, port, targetMs, adaptive) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_io_github_prestgg_hoparlor_NativeEngine_stop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    gEngine.stop();
}

JNIEXPORT void JNICALL Java_io_github_prestgg_hoparlor_NativeEngine_setTarget(JNIEnv*, jobject, jint targetMs,
                                                                            jboolean adaptive) {
    gEngine.setTarget(targetMs, adaptive);
}

JNIEXPORT void JNICALL Java_io_github_prestgg_hoparlor_NativeEngine_stats(JNIEnv* env, jobject, jfloatArray out) {
    if (env->GetArrayLength(out) < hoparlor::kStatCount) return;
    float s[hoparlor::kStatCount];
    gEngine.stats(s);
    env->SetFloatArrayRegion(out, 0, hoparlor::kStatCount, s);
}

}  // extern "C"
