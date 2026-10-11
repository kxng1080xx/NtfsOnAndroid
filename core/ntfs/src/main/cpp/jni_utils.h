#ifndef NTFSFM_JNI_UTILS_H
#define NTFSFM_JNI_UTILS_H

#include <jni.h>
#include <stdbool.h>

/* Remembers the JavaVM so libntfs-3g callbacks (which carry no JNIEnv) can reach Java. */
void jni_utils_init(JavaVM *vm);

/* Returns an env for the calling thread, attaching it if needed. *did_attach says
 * whether the caller must hand it back via jni_utils_detach_if_needed. */
JNIEnv *jni_utils_get_env(bool *did_attach);

void jni_utils_detach_if_needed(bool did_attach);

#endif
