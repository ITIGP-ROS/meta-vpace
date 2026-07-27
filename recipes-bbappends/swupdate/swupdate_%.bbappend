FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
SRC_URI += "file://signing.cfg"

DEPENDS += "systemd"