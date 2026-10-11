#ifndef NTFSFM_NTFS_DEVICE_BRIDGE_H
#define NTFSFM_NTFS_DEVICE_BRIDGE_H

#include <jni.h>
#include <stdbool.h>

#include "ntfs-3g/types.h"
#include "ntfs-3g/device.h"

/* Wraps a Kotlin NativeBlockDeviceBridge as a libntfs-3g device. The bridge object
 * is pinned with a global ref until the device is closed by ntfs_umount. */
struct ntfs_device *ntfs_device_bridge_alloc(JNIEnv *env, jobject block_device_bridge, bool read_only);

#endif
