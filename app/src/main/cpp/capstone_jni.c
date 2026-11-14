#include <jni.h>
#include <android/log.h>
#include <capstone/capstone.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TAG "DexLabNative"

static cs_arch to_arch(jint arch) {
    switch (arch) {
        case 0: return CS_ARCH_ARM;
        case 1: return CS_ARCH_ARM64;
        case 3: return CS_ARCH_X86;
        default: return CS_ARCH_ARM64;
    }
}

static bool has_group(const cs_insn *insn, unsigned int group) {
    if (insn->detail == NULL) return false;
    for (int i = 0; i < insn->detail->groups_count; i++) {
        if (insn->detail->groups[i] == group) return true;
    }
    return false;
}

static unsigned long long branch_target(const cs_insn *insn, cs_arch arch) {
    if (insn->detail == NULL) return 0;
    bool branch = has_group(insn, CS_GRP_JUMP) || has_group(insn, CS_GRP_CALL);
    if (!branch) return 0;
    switch (arch) {
        case CS_ARCH_ARM:
            for (int i = 0; i < insn->detail->arm.op_count; i++) {
                if (insn->detail->arm.operands[i].type == ARM_OP_IMM)
                    return (unsigned long long) insn->detail->arm.operands[i].imm;
            }
            break;
        case CS_ARCH_ARM64:
            for (int i = 0; i < insn->detail->arm64.op_count; i++) {
                if (insn->detail->arm64.operands[i].type == ARM64_OP_IMM)
                    return (unsigned long long) insn->detail->arm64.operands[i].imm;
            }
            break;
        case CS_ARCH_X86:
            for (int i = 0; i < insn->detail->x86.op_count; i++) {
                if (insn->detail->x86.operands[i].type == X86_OP_IMM)
                    return (unsigned long long) insn->detail->x86.operands[i].imm;
            }
            break;
        default:
            break;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_dexlab_app_native_CapstoneBridge_nativeVersion(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return (jint) (CS_VERSION_MAJOR * 10000 + CS_VERSION_MINOR * 100 + CS_VERSION_EXTRA);
}

JNIEXPORT jlong JNICALL
Java_com_dexlab_app_native_CapstoneBridge_nativeOpen(JNIEnv *env, jobject thiz,
                                                     jint arch, jint mode, jboolean detail) {
    (void) env; (void) thiz;
    csh handle = 0;
    if (cs_open(to_arch(arch), (cs_mode) mode, &handle) != CS_ERR_OK) return 0;
    if (detail) cs_option(handle, CS_OPT_DETAIL, CS_OPT_ON);
    return (jlong) handle;
}

JNIEXPORT void JNICALL
Java_com_dexlab_app_native_CapstoneBridge_nativeClose(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env; (void) thiz;
    if (handle == 0) return;
    csh h = (csh) handle;
    cs_close(&h);
}

/*
 * Returns one record per line:
 *   addressHEX \t size \t bytesHEX \t mnemonic \t operands \t branchTargetHEX
 * Returns NULL when nothing could be decoded.
 */
JNIEXPORT jstring JNICALL
Java_com_dexlab_app_native_CapstoneBridge_nativeDisasm(JNIEnv *env, jobject thiz, jlong handle,
                                                       jint arch, jbyteArray code, jlong address,
                                                       jint max) {
    (void) thiz;
    if (handle == 0 || code == NULL) return NULL;
    jsize len = (*env)->GetArrayLength(env, code);
    if (len <= 0) return NULL;

    jbyte *buf = (*env)->GetByteArrayElements(env, code, NULL);
    if (buf == NULL) return NULL;

    cs_arch csArch = to_arch(arch);
    cs_insn *insn = NULL;
    size_t count = cs_disasm((csh) handle, (const uint8_t *) buf, (size_t) len,
                             (uint64_t) address, (size_t) max, &insn);
    (*env)->ReleaseByteArrayElements(env, code, buf, JNI_ABORT);
    if (count == 0 || insn == NULL) {
        cs_free(insn, count);
        return NULL;
    }

    size_t cap = count * 320 + 64;
    char *out = (char *) malloc(cap);
    if (out == NULL) {
        cs_free(insn, count);
        return NULL;
    }
    size_t used = 0;
    for (size_t i = 0; i < count && used + 300 < cap; i++) {
        const cs_insn *cur = &insn[i];
        char bytesHex[3 * 24 + 1];
        bytesHex[0] = '\0';
        size_t pos = 0;
        for (int b = 0; b < cur->size && b < 24; b++) {
            pos += (size_t) snprintf(bytesHex + pos, sizeof(bytesHex) - pos, "%02x", cur->bytes[b]);
        }
        char mnemonic[64];
        char operands[176];
        snprintf(mnemonic, sizeof(mnemonic), "%s", cur->mnemonic);
        snprintf(operands, sizeof(operands), "%s", cur->op_str);
        for (char *p = operands; *p; p++) if (*p == '\t' || *p == '\n') *p = ' ';
        unsigned long long target = branch_target(cur, csArch);
        used += (size_t) snprintf(out + used, cap - used, "%llx\t%d\t%s\t%s\t%s\t%llx\n",
                                  (unsigned long long) cur->address, (int) cur->size,
                                  bytesHex, mnemonic, operands, target);
    }
    cs_free(insn, count);

    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}
