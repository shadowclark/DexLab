#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/*
 * A deliberately tiny, harmless practice library for the DexLab academy.
 * It exports JNI symbols, contains plain strings, and a byte-wise XOR helper.
 */

JNIEXPORT jstring JNICALL
Java_com_dexlab_app_lab_Checker_nativeSecret(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, "the-native-secret-is-arm64");
}

JNIEXPORT jboolean JNICALL
Java_com_dexlab_app_lab_Checker_nativeCheck(JNIEnv *env, jobject thiz, jstring input) {
    (void) thiz;
    if (input == NULL) return JNI_FALSE;
    const char *s = (*env)->GetStringUTFChars(env, input, NULL);
    if (s == NULL) return JNI_FALSE;
    jboolean ok = (jboolean) (strcmp(s, "arm64") == 0);
    (*env)->ReleaseStringUTFChars(env, input, s);
    return ok;
}

JNIEXPORT jbyteArray JNICALL
Java_com_dexlab_app_lab_Checker_xor(JNIEnv *env, jobject thiz, jbyteArray data, jint key) {
    (void) thiz;
    if (data == NULL) return NULL;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (out == NULL) return NULL;
    jbyte *inBuf = (*env)->GetByteArrayElements(env, data, NULL);
    jbyte *outBuf = (*env)->GetByteArrayElements(env, out, NULL);
    if (inBuf == NULL || outBuf == NULL) {
        if (inBuf != NULL) (*env)->ReleaseByteArrayElements(env, data, inBuf, JNI_ABORT);
        if (outBuf != NULL) (*env)->ReleaseByteArrayElements(env, out, outBuf, JNI_ABORT);
        return NULL;
    }
    for (jsize i = 0; i < len; i++) {
        outBuf[i] = (jbyte) (inBuf[i] ^ (jbyte) key);
    }
    (*env)->ReleaseByteArrayElements(env, data, inBuf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, outBuf, 0);
    return out;
}
