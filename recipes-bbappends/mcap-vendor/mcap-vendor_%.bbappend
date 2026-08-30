FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# libmcap.so calls into zstd but its DT_NEEDED omits it, so the mcap storage
# plugin aborts on dlopen with "undefined symbol: ZSTD_CCtx_setParameter" --
# silent by default since rosbag2 uses sqlite3 unless -s mcap is requested.
# meta-ros's own zstd patch dropped the link flag; this restores it.
#
# :append (not +=) so this lands after meta-ros's own SRC_URI patch list.
SRC_URI:append = " file://0001-mcap_vendor-link-libmcap-against-zstd.patch"

# zstd's .pc only reaches the sysroot via zstd-vendor -- say so directly.
DEPENDS += "zstd"
