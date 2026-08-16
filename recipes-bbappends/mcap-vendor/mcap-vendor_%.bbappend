FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# libmcap.so calls into zstd but its DT_NEEDED lists only libc/libgcc/libstdc++,
# so the mcap storage plugin aborts the moment pluginlib dlopens it:
#
#   libmcap.so: undefined symbol: ZSTD_CCtx_setParameter
#   [FATAL] No storage could be initialized. Abort
#
# This is silent by default -- rosbag2 writes sqlite3 unless asked for -s mcap,
# and `ros2 bag list storage` reads the plugin XML without dlopening, so it
# lists mcap as available either way.
#
# The missing link comes from meta-ros's own
# 0001-CMakeLists.txt-fetch-dependencies-with-bitbake-fetch.patch: it swapped
# find_package(zstd) for pkg_check_modules(ZSTD libzstd REQUIRED) but reduced
# `ament_target_dependencies(mcap zstd)` to `ament_target_dependencies(mcap)`,
# leaving the ZSTD_* results unused. We restore the link on top of it.
#
# :append rather than += so this lands after meta-ros's SRC_URI += patch list
# whatever order the two bbappends are parsed in -- our patch has to apply on
# top of theirs.
SRC_URI:append = " file://0001-mcap_vendor-link-libmcap-against-zstd.patch"

# pkg_check_modules(ZSTD libzstd REQUIRED) needs zstd's .pc in the sysroot; it
# only gets there today by way of zstd-vendor. Say so directly.
DEPENDS += "zstd"
