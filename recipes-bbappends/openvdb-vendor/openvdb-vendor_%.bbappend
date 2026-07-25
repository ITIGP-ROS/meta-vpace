# this is not yocto standard, but since this is a "vendor" package they download in the do_compile step

do_compile[network] = "1"


# remap the license from "MPL-2.0-license" to  MPL-2.0
LICENSE = "MPL-2.0"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MPL-2.0;md5=815ca599c9df247a0c7f619bab123dad"


# since this is a "vendor" package it ships prebuilt libs , so i guess we can skip the QA checks for dev-so and staticdev
INSANE_SKIP:${PN} += "dev-so staticdev"