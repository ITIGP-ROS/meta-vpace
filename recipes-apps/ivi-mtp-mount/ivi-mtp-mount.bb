SUMMARY = "Auto-mount USB-connected phones (MTP) where the IVI media browser looks"
DESCRIPTION = "The IVI app looks for phones at gvfs's path layout \
(/run/user/<uid>/gvfs/mtp:host=<name>/<storage>), which this image has none of. That \
lookup is pure QDir, so any FUSE mount at the same path shape works. This packages a \
udev rule, template unit and helper that mount simple-mtpfs there on plug-in and tear \
it down on unplug -- no application changes needed."
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

# simple-mtpfs: the FUSE filesystem. libmtp-common: 69-libmtp.rules, sets
# ID_MTP_DEVICE=1 that our own rule matches on. libmtp-runtime: mtp-probe, used
# to inspect USB descriptors. simple-mtpfs pulls in FUSE 2 alongside the rest of
# the image's FUSE 3 -- expected, not a mistake.
#
# glibc-gconv-utf-16 is the non-obvious one: libmtp needs iconv_open() for UCS-2
# MTP strings, and this image's glibc otherwise ships no UTF-16 converter, so
# every mount fails with "Cannot open iconv() converters to/from UCS-2" even
# though detection (`simple-mtpfs -l`) still works fine.
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

    # Vendor rules dir (/lib/udev/rules.d), alongside libmtp's own rules -- not
    # ${sysconfdir}, which is for local admin overrides.
    install -d ${D}${nonarch_base_libdir}/udev/rules.d
    install -m 0644 ${S}/99-ivi-mtp.rules \
        ${D}${nonarch_base_libdir}/udev/rules.d/99-ivi-mtp.rules
}

FILES:${PN} += "\
    ${systemd_system_unitdir}/ivi-mtp-mount@.service \
    ${nonarch_base_libdir}/udev/rules.d/99-ivi-mtp.rules \
"
