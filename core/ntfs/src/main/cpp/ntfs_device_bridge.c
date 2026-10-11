/*
 * libntfs-3g device backend that forwards byte-addressed I/O to a Kotlin
 * NativeBlockDeviceBridge (a USB mass-storage LUN or an image file) over JNI.
 */

#include "ntfs_device_bridge.h"

#include <android/log.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include "jni_utils.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ntfsbridge/device", __VA_ARGS__)
struct bridge_private {
    jobject block_device_bridge;
    jclass bridge_class;
    jmethodID read_method;
    jmethodID write_method;
    jmethodID flush_method;
    jint sector_size;
    jlong sector_count;
    s64 position;
};

static struct bridge_private *priv(struct ntfs_device *dev) {
    return (struct bridge_private *) dev->d_private;
}

static int bridge_open(struct ntfs_device *dev, int flags) {
    (void) dev;
    (void) flags;
    return 0;
}

static int bridge_close(struct ntfs_device *dev) {
    struct bridge_private *p = priv(dev);
    bool did_attach = false;
    JNIEnv *env = jni_utils_get_env(&did_attach);
    if (env) {
        (*env)->CallBooleanMethod(env, p->block_device_bridge, p->flush_method);
        (*env)->DeleteGlobalRef(env, p->block_device_bridge);
        (*env)->DeleteGlobalRef(env, p->bridge_class);
    }
    jni_utils_detach_if_needed(did_attach);
    free(p);
    dev->d_private = NULL;
    return 0;
}

static s64 bridge_seek(struct ntfs_device *dev, s64 offset, int whence) {
    struct bridge_private *p = priv(dev);
    s64 new_position;
    switch (whence) {
        case SEEK_SET: new_position = offset; break;
        case SEEK_CUR: new_position = p->position + offset; break;
        case SEEK_END: new_position = p->sector_count * p->sector_size + offset; break;
        default: errno = EINVAL; return -1;
    }
    if (new_position < 0) { errno = EINVAL; return -1; }
    p->position = new_position;
    return new_position;
}

/* libntfs-3g reads at arbitrary byte offsets; the block device only speaks whole
 * sectors. Read the covering sector range and copy out the requested window. */
static s64 bridge_pread(struct ntfs_device *dev, void *buf, s64 count, s64 offset) {
    struct bridge_private *p = priv(dev);
    if (count <= 0) return 0;

    /*
     * [first_sector ............................ first_sector + sector_count)
     *      |<- offset_in_sector ->|<---- count ---->|
     * The Java side is handed one array big enough for the whole aligned span,
     * so a single USB transfer serves the request.
     */
    const s64 sector_size = p->sector_size;
    const s64 first_sector = offset / sector_size;
    const s64 offset_in_sector = offset % sector_size;
    const s64 span = offset_in_sector + count;
    const jint sector_count = (jint) ((span + sector_size - 1) / sector_size);
    const s64 aligned_bytes = (s64) sector_count * sector_size;

    bool did_attach = false;
    JNIEnv *env = jni_utils_get_env(&did_attach);
    if (!env) { errno = EIO; return -1; }

    jbyteArray array = (*env)->NewByteArray(env, (jsize) aligned_bytes);
    if (!array) {
        /* NewByteArray left an OutOfMemoryError pending; report ENOMEM instead. */
        (*env)->ExceptionClear(env);
        jni_utils_detach_if_needed(did_attach);
        errno = ENOMEM;
        return -1;
    }

    jint result = (*env)->CallIntMethod(env, p->block_device_bridge, p->read_method,
                                        (jlong) first_sector, sector_count, array);

    s64 transferred = -1;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        errno = EIO;
    } else if (result >= 0) {
        /* Copy out only the bytes libntfs-3g asked for. */
        (*env)->GetByteArrayRegion(env, array, (jsize) offset_in_sector, (jsize) count, (jbyte *) buf);
        transferred = count;
    } else {
        LOGE("read rejected: offset=%lld count=%lld lba=%lld sectors=%d",
             (long long) offset, (long long) count, (long long) first_sector, sector_count);
        errno = EIO;
    }

    (*env)->DeleteLocalRef(env, array);
    jni_utils_detach_if_needed(did_attach);
    return transferred;
}

static s64 bridge_pwrite(struct ntfs_device *dev, const void *buf, s64 count, s64 offset) {
    struct bridge_private *p = priv(dev);
    if (count <= 0) return 0;

    if (NDevReadOnly(dev)) { errno = EROFS; return -1; }

    const s64 sector_size = p->sector_size;
    const s64 first_sector = offset / sector_size;
    const s64 offset_in_sector = offset % sector_size;
    const s64 span = offset_in_sector + count;
    const jint sector_count = (jint) ((span + sector_size - 1) / sector_size);
    const s64 aligned_bytes = (s64) sector_count * sector_size;

    const bool needs_read_modify_write = offset_in_sector != 0 || (span % sector_size) != 0;

    bool did_attach = false;
    JNIEnv *env = jni_utils_get_env(&did_attach);
    if (!env) { errno = EIO; return -1; }

    jbyteArray array = (*env)->NewByteArray(env, (jsize) aligned_bytes);
    if (!array) {
        (*env)->ExceptionClear(env);
        jni_utils_detach_if_needed(did_attach);
        errno = ENOMEM;
        return -1;
    }

    if (needs_read_modify_write) {
        /* A partial sector at either edge: fetch the current contents first so
         * the bytes around the write survive. */
        jint read_result = (*env)->CallIntMethod(env, p->block_device_bridge, p->read_method,
                                                 (jlong) first_sector, sector_count, array);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
            (*env)->DeleteLocalRef(env, array);
            jni_utils_detach_if_needed(did_attach);
            errno = EIO;
            return -1;
        }
        if (read_result < 0) {
            LOGE("read-modify-write: pre-read failed at lba=%lld sectors=%d",
                 (long long) first_sector, sector_count);
            (*env)->DeleteLocalRef(env, array);
            jni_utils_detach_if_needed(did_attach);
            errno = EIO;
            return -1;
        }
    }

    (*env)->SetByteArrayRegion(env, array, (jsize) offset_in_sector, (jsize) count, (const jbyte *) buf);

    jint result = (*env)->CallIntMethod(env, p->block_device_bridge, p->write_method,
                                        (jlong) first_sector, sector_count, array);

    s64 transferred = -1;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        errno = EIO;
    } else if (result >= 0) {
        transferred = count;
    } else {
        LOGE("write rejected: offset=%lld count=%lld lba=%lld sectors=%d",
             (long long) offset, (long long) count, (long long) first_sector, sector_count);
        errno = EIO;
    }

    (*env)->DeleteLocalRef(env, array);
    jni_utils_detach_if_needed(did_attach);
    return transferred;
}

static s64 bridge_read(struct ntfs_device *dev, void *buf, s64 count) {
    struct bridge_private *p = priv(dev);
    s64 n = bridge_pread(dev, buf, count, p->position);
    if (n > 0) p->position += n;
    return n;
}

static s64 bridge_write(struct ntfs_device *dev, const void *buf, s64 count) {
    struct bridge_private *p = priv(dev);
    s64 n = bridge_pwrite(dev, buf, count, p->position);
    if (n > 0) p->position += n;
    return n;
}

static int bridge_sync(struct ntfs_device *dev) {
    struct bridge_private *p = priv(dev);
    bool did_attach = false;
    JNIEnv *env = jni_utils_get_env(&did_attach);
    if (!env) return -1;
    jboolean ok = (*env)->CallBooleanMethod(env, p->block_device_bridge, p->flush_method);
    jni_utils_detach_if_needed(did_attach);
    return ok ? 0 : -1;
}

static int bridge_stat(struct ntfs_device *dev, struct stat *buf) {
    struct bridge_private *p = priv(dev);
    memset(buf, 0, sizeof(*buf));
    buf->st_size = (off_t) p->sector_count * p->sector_size;
    buf->st_mode = S_IFBLK | S_IRUSR | S_IWUSR;
    return 0;
}

static int bridge_ioctl(struct ntfs_device *dev, unsigned long request, void *argp) {
    (void) dev;
    (void) request;
    (void) argp;
    errno = ENOTTY;
    return -1;
}

static struct ntfs_device_operations bridge_ops = {
    .open = bridge_open,
    .close = bridge_close,
    .seek = bridge_seek,
    .read = bridge_read,
    .write = bridge_write,
    .pread = bridge_pread,
    .pwrite = bridge_pwrite,
    .sync = bridge_sync,
    .stat = bridge_stat,
    .ioctl = bridge_ioctl,
};

struct ntfs_device *ntfs_device_bridge_alloc(JNIEnv *env, jobject block_device_bridge, bool read_only) {
    jclass local_class = (*env)->GetObjectClass(env, block_device_bridge);
    jmethodID sector_size_get = (*env)->GetMethodID(env, local_class, "getSectorSize", "()I");
    jmethodID sector_count_get = (*env)->GetMethodID(env, local_class, "getSectorCount", "()J");

    struct bridge_private *p = calloc(1, sizeof(*p));
    p->block_device_bridge = (*env)->NewGlobalRef(env, block_device_bridge);
    p->bridge_class = (*env)->NewGlobalRef(env, local_class);
    p->read_method = (*env)->GetMethodID(env, p->bridge_class, "readSectorsBlocking", "(JI[B)I");
    p->write_method = (*env)->GetMethodID(env, p->bridge_class, "writeSectorsBlocking", "(JI[B)I");
    p->flush_method = (*env)->GetMethodID(env, p->bridge_class, "flushBlocking", "()Z");
    p->sector_size = (*env)->CallIntMethod(env, block_device_bridge, sector_size_get);
    p->sector_count = (*env)->CallLongMethod(env, block_device_bridge, sector_count_get);
    p->position = 0;
    (*env)->DeleteLocalRef(env, local_class);

    /* The name only shows up in libntfs-3g's log lines; state starts clean and
     * read-only is applied below so pwrite can refuse with EROFS. */
    struct ntfs_device *dev = ntfs_device_alloc("usb-bridge", 0, &bridge_ops, p);
    if (!dev) {
        free(p);
        return NULL;
    }
    if (read_only) {
        NDevSetReadOnly(dev);
    }
    return dev;
}
