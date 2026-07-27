SUMMARY = "SecOC WiFi-credential sender: pushes SSID/password to the QNX host over CAN"
DESCRIPTION = "Sends 'SSID;PASSWORD' on CAN 0x205 using ISO-TP segmentation, \
authenticated with a truncated AES-128-CMAC and a monotonic freshness counter."
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

# The freshness counter lives here. It MUST survive reboot — see README.
FVDIR = "${localstatedir}/lib"

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
    install -m 0400 ${S}/secoc.key ${D}${sysconfdir}/wifi_secoc.key

    install -d ${D}${FVDIR}
}

FILES:${PN} += "${sysconfdir}/wifi_secoc.key"
