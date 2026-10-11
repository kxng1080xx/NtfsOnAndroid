/* Hand-written config.h for building libntfs-3g with the Android NDK (bionic).
 * Replaces the autoconf-generated one; only what libntfs-3g's sources test for. */
#ifndef NTFS_ANDROID_CONFIG_H
#define NTFS_ANDROID_CONFIG_H

#define PACKAGE_NAME "ntfs-3g"
#define PACKAGE_VERSION "2026.9.28"
#define VERSION PACKAGE_VERSION

#define HAVE_BYTESWAP_H 1
#define HAVE_CTYPE_H 1
#define HAVE_ENDIAN_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_LINUX_FD_H 1
#define HAVE_LINUX_FS_H 1
#define HAVE_LINUX_HDREG_H 1
#define HAVE_LOCALE_H 1
#define HAVE_STDARG_H 1
#define HAVE_STDDEF_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_SYSLOG_H 1
#define HAVE_SYS_IOCTL_H 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_SYSMACROS_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_TIME_H 1
#define HAVE_UNISTD_H 1
#define HAVE_WCHAR_H 1

#define HAVE_FFS 1
#define HAVE_GETTIMEOFDAY 1
#define HAVE_MBSINIT 1
#define HAVE_REALPATH 1
#define HAVE_SETLOCALE 1
#define HAVE_STRSEP 1

/* bionic has no <mntent.h> worth using and nothing here is mounted through the kernel. */
#undef HAVE_MNTENT_H

/* glibc drags these in through other headers; bionic does not. */
#include <strings.h>        /* ffs() */
#include <sys/sysmacros.h>  /* major(), minor(), makedev() */
#include <sys/stat.h>
#ifndef S_IREAD
#define S_IREAD S_IRUSR
#endif
#ifndef S_IWRITE
#define S_IWRITE S_IWUSR
#endif
#ifndef S_IEXEC
#define S_IEXEC S_IXUSR
#endif

#endif
