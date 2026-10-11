/*
 * JNI entry points for dev.ntfsfm.core.ntfs.NtfsNative, backed by libntfs-3g.
 *
 * Volume and file handles cross into Kotlin as jlong-cast pointers. Paths are
 * absolute, '/'-separated, UTF-8; "" and "/" both mean the root directory.
 */

#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

#include "ntfs-3g/types.h"
#include "ntfs-3g/attrib.h"
#include "ntfs-3g/dir.h"
#include "ntfs-3g/inode.h"
#include "ntfs-3g/ntfstime.h"
#include "ntfs-3g/unistr.h"
#include "ntfs-3g/volume.h"

#include "jni_utils.h"
#include "ntfs_device_bridge.h"

#define LOG_TAG "ntfsbridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* What openFile hands to Kotlin: the inode plus its open unnamed $DATA
 * attribute. closeFile releases both. */
struct file_handle {
    ntfs_inode *inode;
    ntfs_attr *data_attr;
};

/*
 * NTFS times count 100ns intervals since 1601-01-01; Kotlin wants epoch
 * millis. An all-zero NTFS time means "unset" and maps to 0, not to 1601,
 * so a never-accessed file does not show up as 425 years old.
 */
static jlong ntfs_time_to_epoch_millis(ntfs_time t) {
    if (t == 0) return 0;
    struct timespec ts = ntfs2timespec(t);
    return (jlong) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static ntfs_time epoch_millis_to_ntfs_time(jlong epoch_millis) {
    struct timespec ts;
    ts.tv_sec = (time_t) (epoch_millis / 1000);
    ts.tv_nsec = (long) ((epoch_millis % 1000) * 1000000);
    return timespec2ntfs(ts);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    jni_utils_init(vm);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_mount(JNIEnv *env, jobject thiz, jobject device, jboolean read_only) {
    (void) thiz;
    struct ntfs_device *dev = ntfs_device_bridge_alloc(env, device, read_only != JNI_FALSE);
    if (!dev) {
        LOGE("failed to allocate bridge device");
        return 0;
    }

    /*
     * NTFS_MNT_RECOVER: a volume Windows left with a dirty $LogFile (fast
     * startup, yanked cable) is otherwise refused outright. ntfs-3g can only
     * reset the journal, not replay it, which is what ntfsfix does too and is
     * what every other non-Windows NTFS driver settles for.
     *
     * NTFS_MNT_MAY_RDONLY: if the volume is hibernated or otherwise unsafe to
     * write, fall back to a read-only mount instead of failing, so the user
     * can at least copy files off. Kotlin checks isVolumeReadOnly afterwards
     * and tells the user why writes are disabled.
     *
     * An explicit read-only request skips MAY_RDONLY: there is nothing to fall
     * back from.
     */
    ntfs_mount_flags flags = read_only
            ? (NTFS_MNT_RDONLY | NTFS_MNT_RECOVER)
            : (NTFS_MNT_RECOVER | NTFS_MNT_MAY_RDONLY);

    ntfs_volume *vol = ntfs_device_mount(dev, flags);
    if (!vol) {
        LOGE("ntfs_device_mount failed: errno=%d (%s)", errno, strerror(errno));
        ntfs_device_free(dev);
        return 0;
    }
    LOGI("mounted NTFS volume: %lld bytes, cluster=%u, read_only=%d",
         (long long) (vol->nr_clusters * vol->cluster_size), vol->cluster_size,
         NVolReadOnly(vol) ? 1 : 0);

    return (jlong) (intptr_t) vol;
}

JNIEXPORT void JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_unmount(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    if (vol) ntfs_umount(vol, FALSE);
}

JNIEXPORT jboolean JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_isVolumeDirty(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    if (!vol) return JNI_TRUE;
    /* The dirty bit in $Volume's VOLUME_INFORMATION, i.e. what chkdsk would be
     * scheduled for. Separate from the $LogFile state that NTFS_MNT_RECOVER
     * resets at mount. */
    return (le16_to_cpu(vol->flags) & VOLUME_IS_DIRTY) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_isVolumeReadOnly(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    if (!vol) return JNI_TRUE;
    /* True for an explicit read-only mount and for a MAY_RDONLY fallback
     * (hibernated volume, unclean journal that could not be reset). */
    return NVolReadOnly(vol) ? JNI_TRUE : JNI_FALSE;
}

static ntfs_inode *resolve_path(ntfs_volume *vol, const char *path) {
    if (path == NULL || path[0] == '\0') {
        return ntfs_inode_open(vol, FILE_root);
    }
    return ntfs_pathname_to_inode(vol, NULL, path);
}

static jobject build_native_dir_entry(JNIEnv *env, const char *name, ntfs_inode *ni) {
    jclass cls = (*env)->FindClass(env, "dev/ntfsfm/core/ntfs/NativeDirEntry");
    jmethodID ctor = (*env)->GetMethodID(
            env, cls, "<init>", "(Ljava/lang/String;ZJZZZZJJJ)V");

    jboolean is_dir = (ni->mrec->flags & MFT_RECORD_IS_DIRECTORY) ? JNI_TRUE : JNI_FALSE;
    jlong size = is_dir ? 0 : ni->data_size;
    jboolean read_only = (ni->flags & FILE_ATTR_READONLY) ? JNI_TRUE : JNI_FALSE;
    jboolean hidden = (ni->flags & FILE_ATTR_HIDDEN) ? JNI_TRUE : JNI_FALSE;
    jboolean system = (ni->flags & FILE_ATTR_SYSTEM) ? JNI_TRUE : JNI_FALSE;
    jboolean archive = (ni->flags & FILE_ATTR_ARCHIVE) ? JNI_TRUE : JNI_FALSE;

    jstring jname = (*env)->NewStringUTF(env, name);
    jobject result = (*env)->NewObject(env, cls, ctor,
            jname, is_dir, size, read_only, hidden, system, archive,
            ntfs_time_to_epoch_millis(ni->creation_time),
            ntfs_time_to_epoch_millis(ni->last_data_change_time),
            ntfs_time_to_epoch_millis(ni->last_access_time));
    (*env)->DeleteLocalRef(env, jname);
    (*env)->DeleteLocalRef(env, cls);
    return result;
}

struct readdir_collector {
    MFT_REF *refs;
    char **names;
    int count;
    int capacity;
};

static int filldir_collect(void *dirent, const ntfschar *name, const int name_len, const int name_type,
                           const s64 pos, const MFT_REF mref, const unsigned dt_type) {
    (void) pos;
    (void) dt_type;
    if (name_type == FILE_NAME_DOS) return 0;

    struct readdir_collector *collector = dirent;
    if (collector->count == collector->capacity) {
        collector->capacity = collector->capacity == 0 ? 32 : collector->capacity * 2;
        collector->refs = realloc(collector->refs, sizeof(MFT_REF) * collector->capacity);
        collector->names = realloc(collector->names, sizeof(char *) * collector->capacity);
    }

    char *utf8_name = NULL;
    int utf8_len = ntfs_ucstombs(name, name_len, &utf8_name, 0);
    if (utf8_len < 0) return 0;

    collector->refs[collector->count] = mref;
    collector->names[collector->count] = utf8_name;
    collector->count++;
    return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_listDirectory(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    const char *path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);
    ntfs_inode *dir_ni = resolve_path(vol, path_utf8);
    (*env)->ReleaseStringUTFChars(env, path, path_utf8);

    jclass entry_class = (*env)->FindClass(env, "dev/ntfsfm/core/ntfs/NativeDirEntry");
    if (!dir_ni) {
        return (*env)->NewObjectArray(env, 0, entry_class, NULL);
    }

    struct readdir_collector collector = {0};
    s64 pos = 0;
    ntfs_readdir(dir_ni, &pos, &collector, filldir_collect);

    jobjectArray result = (*env)->NewObjectArray(env, collector.count, entry_class, NULL);
    for (int i = 0; i < collector.count; i++) {
        ntfs_inode *child = ntfs_inode_open(vol, collector.refs[i]);
        if (child) {
            jobject entry = build_native_dir_entry(env, collector.names[i], child);
            (*env)->SetObjectArrayElement(env, result, i, entry);
            (*env)->DeleteLocalRef(env, entry);
            ntfs_inode_close(child);
        }
        free(collector.names[i]);
    }
    free(collector.refs);
    free(collector.names);
    ntfs_inode_close(dir_ni);
    return result;
}

JNIEXPORT jobject JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_stat(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    const char *path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);
    ntfs_inode *ni = resolve_path(vol, path_utf8);
    const char *base_name = strrchr(path_utf8, '/');
    base_name = base_name ? base_name + 1 : path_utf8;

    jobject result = NULL;
    if (ni) {
        result = build_native_dir_entry(env, base_name[0] ? base_name : "", ni);
        ntfs_inode_close(ni);
    }
    (*env)->ReleaseStringUTFChars(env, path, path_utf8);
    return result;
}

JNIEXPORT jlong JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_openFile(JNIEnv *env, jobject thiz, jlong handle, jstring path, jboolean for_write) {
    (void) thiz;
    /* for_write only feeds the log: ntfs-3g attributes are always opened
     * read/write and the mount mode decides whether writes go through. */
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    const char *path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);
    ntfs_inode *ni = resolve_path(vol, path_utf8);
    (*env)->ReleaseStringUTFChars(env, path, path_utf8);
    if (!ni) return 0;

    ntfs_attr *na = ntfs_attr_open(ni, AT_DATA, AT_UNNAMED, 0);
    if (!na) {
        LOGE("ntfs_attr_open(AT_DATA) failed (for_write=%d): errno=%d (%s)",
             for_write, errno, strerror(errno));
        ntfs_inode_close(ni);
        return 0;
    }

    struct file_handle *fh = malloc(sizeof(*fh));
    fh->inode = ni;
    fh->data_attr = na;
    return (jlong) (intptr_t) fh;
}

JNIEXPORT void JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_closeFile(JNIEnv *env, jobject thiz, jlong file_handle) {
    (void) env;
    (void) thiz;
    struct file_handle *fh = (struct file_handle *) (intptr_t) file_handle;
    if (!fh) return;
    ntfs_attr_close(fh->data_attr);
    ntfs_inode_close(fh->inode);
    free(fh);
}

JNIEXPORT jint JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_readFile(JNIEnv *env, jobject thiz, jlong file_handle,
        jbyteArray buffer, jint offset, jint length, jlong file_position) {
    (void) thiz;
    struct file_handle *fh = (struct file_handle *) (intptr_t) file_handle;
    jbyte *native_buf = malloc((size_t) length);
    s64 n = ntfs_attr_pread(fh->data_attr, file_position, length, native_buf);
    if (n > 0) (*env)->SetByteArrayRegion(env, buffer, offset, (jsize) n, native_buf);
    free(native_buf);
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_writeFile(JNIEnv *env, jobject thiz, jlong file_handle,
        jbyteArray buffer, jint offset, jint length, jlong file_position) {
    (void) thiz;
    struct file_handle *fh = (struct file_handle *) (intptr_t) file_handle;
    if (!fh) return -1;

    jbyte *native_buf = malloc((size_t) length);
    if (!native_buf) return -1;

    (*env)->GetByteArrayRegion(env, buffer, offset, length, native_buf);
    s64 n = ntfs_attr_pwrite(fh->data_attr, file_position, length, native_buf);
    free(native_buf);

    if (n < 0) {
        LOGE("ntfs_attr_pwrite(pos=%lld len=%d) failed: errno=%d (%s)",
             (long long) file_position, length, errno, strerror(errno));
    } else {
        /* Size and mtime live in the inode; without this the new length is
         * lost when the handle is closed. */
        ntfs_inode_mark_dirty(fh->inode);
    }
    return (jint) n;
}

JNIEXPORT jlong JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_freeSpaceBytes(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    if (!vol) return 0;
    /*
     * free_clusters is only meaningful once ntfs-3g has scanned $Bitmap.
     * ntfs_device_mount does that lazily (NVolFreeSpaceKnown stays clear
     * until someone asks), so a freshly mounted volume would report 0 bytes
     * free. Trigger the scan here on first use; afterwards ntfs-3g keeps
     * free_clusters current as clusters are allocated and freed.
     */

    if (!NVolFreeSpaceKnown(vol)) {
        if (ntfs_volume_get_free_space(vol)) {
            LOGE("ntfs_volume_get_free_space failed: errno=%d (%s)", errno, strerror(errno));
            return 0;
        }
        LOGI("free space computed: %lld bytes free of %lld",
             (long long) (vol->free_clusters * vol->cluster_size),
             (long long) (vol->nr_clusters * vol->cluster_size));
    }
    return vol->free_clusters * vol->cluster_size;
}

JNIEXPORT jlong JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_totalSpaceBytes(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    return vol->nr_clusters * vol->cluster_size;
}

/*
 * Splits "/a/b/name" into an open inode for "/a/b" and "name" as ntfschar.
 * On success the caller owns *dir_ni (close it, unless ntfs_delete already
 * did) and *name (free it). Returns 0 on success, -1 on failure with nothing
 * left open.
 */

static int split_parent_and_name(ntfs_volume *vol, const char *path,
                                 ntfs_inode **dir_ni, ntfschar **name, int *name_len) {
    *dir_ni = NULL;
    *name = NULL;
    *name_len = 0;

    const char *last_slash = strrchr(path, '/');
    const char *base_name = last_slash ? last_slash + 1 : path;
    if (*base_name == '\0') return -1;

    if (!last_slash) {
        *dir_ni = ntfs_inode_open(vol, FILE_root);
    } else {
        size_t parent_len = (size_t) (last_slash - path);
        if (parent_len == 0) {
            *dir_ni = ntfs_inode_open(vol, FILE_root);
        } else {
            char *parent_path = malloc(parent_len + 1);
            if (!parent_path) return -1;
            memcpy(parent_path, path, parent_len);
            parent_path[parent_len] = '\0';
            *dir_ni = ntfs_pathname_to_inode(vol, NULL, parent_path);
            free(parent_path);
        }
    }
    if (!*dir_ni) return -1;

    int len = ntfs_mbstoucs(base_name, name);
    if (len < 0) {
        ntfs_inode_close(*dir_ni);
        *dir_ni = NULL;
        return -1;
    }
    *name_len = len;
    return 0;
}

/* Shared by createFile and createDirectory; type is S_IFREG or S_IFDIR. */
static jboolean create_entry(JNIEnv *env, jlong handle, jstring path, mode_t type) {
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    if (!vol) return JNI_FALSE;

    const char *path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);

    ntfs_inode *dir_ni = NULL;
    ntfschar *name = NULL;
    int name_len = 0;
    jboolean ok = JNI_FALSE;

    if (split_parent_and_name(vol, path_utf8, &dir_ni, &name, &name_len) == 0) {
        /* securid 0: no explicit security descriptor, so the new entry
         * inherits from its parent the way Explorer-created files do. This
         * also keeps the volume readable by Windows without permission fixes. */
        ntfs_inode *ni = ntfs_create(dir_ni, 0, name, name_len, type);
        if (ni) {
            /* Kotlin re-opens by path for any I/O; nothing to keep the new
             * inode open for. */
            ntfs_inode_close(ni);
            ok = JNI_TRUE;
        } else {
            LOGE("ntfs_create failed for '%s': errno=%d (%s)", path_utf8, errno, strerror(errno));
        }
        ntfs_inode_close(dir_ni);
        free(name);
    }

    (*env)->ReleaseStringUTFChars(env, path, path_utf8);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_createFile(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    (void) thiz;
    return create_entry(env, handle, path, S_IFREG);
}

JNIEXPORT jboolean JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_createDirectory(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    (void) thiz;
    return create_entry(env, handle, path, S_IFDIR);
}

JNIEXPORT jboolean JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_truncateFile(JNIEnv *env, jobject thiz, jlong file_handle, jlong size_bytes) {
    (void) env;
    (void) thiz;
    struct file_handle *fh = (struct file_handle *) (intptr_t) file_handle;
    if (!fh) return JNI_FALSE;

    if (ntfs_attr_truncate(fh->data_attr, size_bytes)) {
        LOGE("ntfs_attr_truncate to %lld failed: errno=%d (%s)",
             (long long) size_bytes, errno, strerror(errno));
        return JNI_FALSE;
    }
    ntfs_inode_mark_dirty(fh->inode);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_delete(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    if (!vol) return JNI_FALSE;

    const char *path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);

    ntfs_inode *dir_ni = NULL;
    ntfschar *name = NULL;
    int name_len = 0;
    jboolean ok = JNI_FALSE;

    if (split_parent_and_name(vol, path_utf8, &dir_ni, &name, &name_len) == 0) {
        ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, path_utf8);
        LOGI("delete '%s': ni=%p dir_ni=%p name=%p name_len=%d",
             path_utf8, (void *) ni, (void *) dir_ni, (void *) name, name_len);
        if (ni) {
            /* ntfs_delete closes both ni and dir_ni, whether or not it
             * succeeds. */
            if (ntfs_delete(vol, path_utf8, ni, dir_ni, name, name_len) == 0) {
                ok = JNI_TRUE;
            } else {
                LOGE("ntfs_delete failed for '%s': errno=%d (%s)", path_utf8, errno, strerror(errno));
            }
            dir_ni = NULL;
        } else {
            LOGE("delete: '%s' not found", path_utf8);
        }
        if (dir_ni) ntfs_inode_close(dir_ni);
        free(name);
    }

    (*env)->ReleaseStringUTFChars(env, path, path_utf8);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_rename(JNIEnv *env, jobject thiz, jlong handle, jstring old_path, jstring new_path) {
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    if (!vol) return JNI_FALSE;

    const char *old_utf8 = (*env)->GetStringUTFChars(env, old_path, NULL);
    const char *new_utf8 = (*env)->GetStringUTFChars(env, new_path, NULL);

    ntfs_inode *dir_ni = NULL;
    ntfschar *new_name = NULL;
    int new_name_len = 0;
    jboolean ok = JNI_FALSE;

    /*
     * libntfs-3g has no rename: link the inode under the new name, then
     * unlink the old one, the same sequence ntfs-3g's FUSE driver uses.
     */
    if (split_parent_and_name(vol, new_utf8, &dir_ni, &new_name, &new_name_len) == 0) {
        ntfs_inode *ni = ntfs_pathname_to_inode(vol, NULL, old_utf8);
        if (ni) {
            if (ntfs_link(ni, dir_ni, new_name, new_name_len) == 0) {
                /* ntfs_delete below wants its own freshly opened inode. */
                ntfs_inode_close(ni);

                ntfs_inode *old_dir_ni = NULL;
                ntfschar *old_name = NULL;
                int old_name_len = 0;
                if (split_parent_and_name(vol, old_utf8, &old_dir_ni, &old_name, &old_name_len) == 0) {
                    ntfs_inode *old_ni = ntfs_pathname_to_inode(vol, NULL, old_utf8);
                    if (old_ni && ntfs_delete(vol, old_utf8, old_ni, old_dir_ni,
                                              old_name, old_name_len) == 0) {
                        ok = JNI_TRUE;
                    } else {
                        LOGE("rename: unlinking old name '%s' failed: errno=%d", old_utf8, errno);
                        if (!old_ni) ntfs_inode_close(old_dir_ni);
                    }
                    free(old_name);
                }
            } else {
                LOGE("ntfs_link '%s' -> '%s' failed: errno=%d (%s)",
                     old_utf8, new_utf8, errno, strerror(errno));
                ntfs_inode_close(ni);
            }
        } else {
            LOGE("rename: '%s' not found", old_utf8);
        }
        ntfs_inode_close(dir_ni);
        free(new_name);
    }

    (*env)->ReleaseStringUTFChars(env, old_path, old_utf8);
    (*env)->ReleaseStringUTFChars(env, new_path, new_utf8);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_setAttributes(JNIEnv *env, jobject thiz, jlong handle, jstring path,
        jboolean read_only, jboolean hidden, jboolean system, jboolean archive) {
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    if (!vol) return JNI_FALSE;

    const char *path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);
    ntfs_inode *ni = resolve_path(vol, path_utf8);
    (*env)->ReleaseStringUTFChars(env, path, path_utf8);
    if (!ni) return JNI_FALSE;
    /*
     * Only the four user-facing bits are ours to change. Everything else in
     * the flags (directory, compressed, sparse, reparse...) is owned by NTFS.
     */
    le32 preserved = ni->flags & ~(FILE_ATTR_READONLY | FILE_ATTR_HIDDEN
                                   | FILE_ATTR_SYSTEM | FILE_ATTR_ARCHIVE);
    le32 updated = preserved;
    if (read_only) updated |= FILE_ATTR_READONLY;
    if (hidden) updated |= FILE_ATTR_HIDDEN;
    if (system) updated |= FILE_ATTR_SYSTEM;
    if (archive) updated |= FILE_ATTR_ARCHIVE;

    ni->flags = updated;
    ntfs_inode_mark_dirty(ni);
    int rc = ntfs_inode_close(ni);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_ntfsfm_core_ntfs_NtfsNative_setTimestamps(JNIEnv *env, jobject thiz, jlong handle, jstring path,
        jlong created_at_millis, jlong modified_at_millis) {
    (void) thiz;
    ntfs_volume *vol = (ntfs_volume *) (intptr_t) handle;
    if (!vol) return JNI_FALSE;

    const char *path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);
    ntfs_inode *ni = resolve_path(vol, path_utf8);
    (*env)->ReleaseStringUTFChars(env, path, path_utf8);
    if (!ni) return JNI_FALSE;

    ni->creation_time = epoch_millis_to_ntfs_time(created_at_millis);
    ni->last_data_change_time = epoch_millis_to_ntfs_time(modified_at_millis);
    /* Written back to $STANDARD_INFORMATION when the inode is closed;
     * the directory index copy follows on the next lookup. */
    ntfs_inode_mark_dirty(ni);
    int rc = ntfs_inode_close(ni);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}
