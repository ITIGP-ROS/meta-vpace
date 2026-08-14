SUMMARY = "Auto-mount USB-connected phones (MTP) where the IVI media browser looks"
DESCRIPTION = "The IVI app already implements MTP phone support in \
Backend/USBManager.cpp, but it looks for the mount at gvfs's path layout \
(/run/user/<uid>/gvfs/mtp:host=<name>/<storage>) and this image has neither gvfs nor any \
MTP filesystem, so nothing ever appears. That lookup is pure QDir -- no gvfs API, no \
D-Bus -- so any FUSE mount at the same path shape satisfies it. This packages a udev \
rule, a systemd template unit and a helper that mount simple-mtpfs exactly there when a \
phone is plugged in, and tear it down on unplug. No application changes are involved."
LICENSE = "CLOSED"

PV = "1.0"

SRC_URI = "\
    file://ivi-mtp-mount \
    file://ivi-mtp-mount@.service \
    file://99-ivi-mtp.rules \
"

# Portable across Yocto releases: scarthgap+ defines UNPACKDIR (files land in
# ${WORKDIR}/sources-unpack); older releases unpack straight into ${WORKDIR}.
# The ?= keeps the newer definition and supplies the old default otherwise.
UNPACKDIR ?= "${WORKDIR}"
S = "${UNPACKDIR}"

inherit systemd

SYSTEMD_SERVICE:${PN} = "ivi-mtp-mount@.service"
# A template has nothing to enable, and it must not be pulled in at boot: with no phone
# attached there is no device to mount. udev instantiates it per device instead.
SYSTEMD_AUTO_ENABLE = "disable"

# simple-mtpfs    -> the FUSE filesystem itself (meta-filesystems).
# libmtp-common   -> 69-libmtp.rules, which is what sets ID_MTP_DEVICE=1. Without it our
#                    own rule never matches and nothing is ever mounted.
# libmtp-runtime  -> mtp-probe, which those rules invoke to inspect the USB interface
#                    descriptors. This is why phones missing from libmtp's device table
#                    still get detected.
#
# NOTE on 'fuse': simple-mtpfs DEPENDS on FUSE *2* (fuse_2.9.9.bb), while the rest of this
# image is on FUSE 3 (fusermount3, libfuse3). Both end up installed; that is expected, not
# a packaging mistake, and is why the helper script tries both unmount binaries.
#
# NOTE on layer priority: meta-filesystems is priority 5 and meta-ros2-humble is 12. ROS
# also ships a recipe called 'fuse' -- an unrelated sensor-fusion package -- but only in
# the noetic/jazzy/kilted/rolling layers, none of which are in this build. If the ROS
# distro is ever changed, check that 'fuse' still resolves to the filesystem one.
# glibc-gconv-utf-16 is THE non-obvious one, and without it nothing works.
#
# MTP strings are UCS-2, so libmtp calls iconv_open() the moment it opens a session.
# This image's glibc ships exactly ONE converter -- /usr/lib/gconv/ISO8859-1.so -- so
# that call fails and the device never opens:
#
#     LIBMTP PANIC: Cannot open iconv() converters to/from UCS-2!
#     Too old stdlibc, glibc and libiconv?
#     Unable to open raw device 0
#
# The trap is that DETECTION still works, because that only reads USB descriptors:
# `simple-mtpfs -l` happily prints the phone while every mount fails. It also works on
# a desktop distro, which ships the full gconv set -- so "it works on my laptop" is
# expected and tells you nothing.
#
# Determined by bisection against a real Samsung on 2026-08-14: UTF-16.so alone is
# sufficient. UNICODE.so on its own is NOT (the mount still fails), and UTF-32.so is
# not needed at all. If a future device fails the same way, `mtp-detect` from
# libmtp-bin prints the iconv error directly -- simple-mtpfs only ever says
# "LIBMTP PANIC ... NULL device", which points nowhere.
RDEPENDS:${PN} = "\
    simple-mtpfs \
    libmtp-common \
    libmtp-runtime \
    glibc-gconv-utf-16 \
"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${S}/ivi-mtp-mount ${D}${bindir}/ivi-mtp-mount

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${S}/ivi-mtp-mount@.service \
        ${D}${systemd_system_unitdir}/ivi-mtp-mount@.service

    # ${nonarch_base_libdir}/udev/rules.d, i.e. /lib/udev/rules.d -- the vendor rules
    # directory, alongside libmtp's own 69-libmtp.rules. Not ${sysconfdir}/udev/rules.d,
    # which is where a local admin override would go.
    install -d ${D}${nonarch_base_libdir}/udev/rules.d
    install -m 0644 ${S}/99-ivi-mtp.rules \
        ${D}${nonarch_base_libdir}/udev/rules.d/99-ivi-mtp.rules
}

FILES:${PN} += "\
    ${systemd_system_unitdir}/ivi-mtp-mount@.service \
    ${nonarch_base_libdir}/udev/rules.d/99-ivi-mtp.rules \
"
