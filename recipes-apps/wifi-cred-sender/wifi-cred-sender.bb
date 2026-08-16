SUMMARY = "SecOC WiFi-credential sender: pushes SSID/password to the QNX host and the ESP32 over CAN"
DESCRIPTION = "Sends 'SSID;PASSWORD' using ISO-TP segmentation, authenticated \
with a truncated AES-128-CMAC and a monotonic freshness counter. Two receivers, \
one ISO-TP session each: the QNX host on 0x205/0x206 and the ESP32 body ECU on \
0x207/0x208. Select with -t/--target cluster|esp32|both."
LICENSE = "CLOSED"
LIC_FILES_CHKSUM = ""

PV = "1.0"

SRC_URI = "\
    file://wifi_cred_send.cpp \
    file://aes_cmac.c \
    file://aes_cmac.h \
    file://secoc.key \
"

# Portable across Yocto releases: scarthgap+ defines UNPACKDIR (files land in
# ${WORKDIR}/sources-unpack); older releases unpack straight into ${WORKDIR}.
# The ?= keeps the newer definition and supplies the old default otherwise.
UNPACKDIR ?= "${WORKDIR}"
S = "${UNPACKDIR}"

# The freshness counter lives at /data/secoc/wifi_cred_txfv (compiled into
# wifi_cred_send.cpp as the default, overridable with -F).
#
# It is NOT staged from this recipe, and there is no FVDIR any more. /data is
# nvme0n1p15, a mount point — anything installed into it at image build time is
# hidden the moment the partition mounts and would never be seen. The file and
# its weston ownership are created at boot by mount-data-partition.sh, which is
# also the only place that can check the mount actually succeeded.
#
# It lives there rather than under /var/lib because it must survive a FLASH,
# not merely a reboot: the rootfs is the A/B pair SWUpdate replaces, while the
# QNX cluster and the ESP32 keep their own freshness floors across it.

do_compile() {
    ${CC} ${CFLAGS} -c -o aes_cmac.o ${S}/aes_cmac.c
    ${CXX} ${CXXFLAGS} -std=c++17 -I${S} -c -o wifi_cred_send.o ${S}/wifi_cred_send.cpp
    ${CXX} ${LDFLAGS} -o wifi_cred_send wifi_cred_send.o aes_cmac.o
}

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${B}/wifi_cred_send ${D}${bindir}/wifi_cred_send

    # Install the secret key
    install -d ${D}${sysconfdir}
    install -m 0444 ${S}/secoc.key ${D}${sysconfdir}/wifi_secoc.key
}

RDEPENDS:${PN} += "data-partition-mount"

FILES:${PN} += "${sysconfdir}/wifi_secoc.key"
