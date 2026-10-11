cmake_minimum_required(VERSION 3.22.1)
project(ntfsbridge C)

set(NTFS3G_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ntfs-3g)

# Only the libntfs-3g pieces a mount/read/write/create/delete/rename path pulls in;
# no FUSE, no ntfsprogs, no EFS/xattr/ioctl front ends.
set(NTFS3G_SOURCES
    acls attrib attrlist bitmap bootsect cache collate compress device dir ea
    index inode lcnalloc logfile logging mft misc mst object_id reparse runlist
    security unistr unix_io volume
)
list(TRANSFORM NTFS3G_SOURCES PREPEND ${NTFS3G_DIR}/libntfs-3g/)
list(TRANSFORM NTFS3G_SOURCES APPEND .c)

add_library(ntfsbridge SHARED
    ntfs_bridge.c
    ntfs_device_bridge.c
    jni_utils.c
    ${NTFS3G_SOURCES}
)

target_include_directories(ntfsbridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${NTFS3G_DIR}
    ${NTFS3G_DIR}/include
    ${NTFS3G_DIR}/include/ntfs-3g
)
target_compile_definitions(ntfsbridge PRIVATE HAVE_CONFIG_H _FILE_OFFSET_BITS=64)
# libntfs-3g is old C that trips modern clang's defaults; none of these hide bugs in our code.
target_compile_options(ntfsbridge PRIVATE
    -Wno-unused-parameter -Wno-sign-compare -Wno-pointer-sign -Wno-format
    -Wno-deprecated-non-prototype -Wno-unused-but-set-variable -Wno-address-of-packed-member
)
target_link_libraries(ntfsbridge log)
