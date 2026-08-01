# Override sw-description with sha256 hashes added to all entries (required by CONFIG_SIGNED_IMAGES).
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

ROOTFS_FILENAME = "${SWUPDATE_CORE_IMAGE_NAME}-humble-${MACHINE}.rootfs.tar.gz"
