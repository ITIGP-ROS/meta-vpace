SUMMARY = "CAN node-liveness responder: echoes the host's ping (0x7A0) on 0x7A2"
DESCRIPTION = "Listens on can0 for the host's rolling NodePingRequest (0x7A0, \
1 byte seq) and echoes the byte unchanged on NodePingRespJetson (0x7A2). \
Answers unconditionally — no init/ready gating, no SecOC: the point is to \
prove the node is powered and its CAN stack is alive. The response ID is \
overridable (-r) so the same binary could serve the Tiva (0x7A1) or ESP32 \
(0x7A3) nodes."
LICENSE = "CLOSED"
LIC_FILES_CHKSUM = ""

PV = "1.0"

SRC_URI = "\
    file://liveliness_respond.cpp \
    file://liveliness-respond.service \
"

# Portable across Yocto releases: scarthgap+ defines UNPACKDIR (files land in
# ${WORKDIR}/sources-unpack); older releases unpack straight into ${WORKDIR}.
# The ?= keeps the newer definition and supplies the old default otherwise.
UNPACKDIR ?= "${WORKDIR}"
S = "${UNPACKDIR}"

inherit systemd

SYSTEMD_SERVICE:${PN} = "liveliness-respond.service"
SYSTEMD_AUTO_ENABLE = "enable"

do_compile() {
    ${CXX} ${CXXFLAGS} ${LDFLAGS} -std=c++17 -o liveliness_respond ${S}/liveliness_respond.cpp
}

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${B}/liveliness_respond ${D}${bindir}/liveliness_respond

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${S}/liveliness-respond.service ${D}${systemd_system_unitdir}/liveliness-respond.service
}

FILES:${PN} += "${systemd_system_unitdir}/liveliness-respond.service"
