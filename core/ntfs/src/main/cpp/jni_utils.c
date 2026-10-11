#include "jni_utils.h"

static JavaVM *g_vm = NULL;

void jni_utils_init(JavaVM *vm) {
    g_vm = vm;
}

JNIEnv *jni_utils_get_env(bool *did_attach) {
    *did_attach = false;
    if (!g_vm) return NULL;

    JNIEnv *env = NULL;
    jint result = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            return NULL;
        }
        *did_attach = true;
    } else if (result != JNI_OK) {
        return NULL;
    }
    return env;
}

void jni_utils_detach_if_needed(bool did_attach) {
    if (did_attach && g_vm) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}
