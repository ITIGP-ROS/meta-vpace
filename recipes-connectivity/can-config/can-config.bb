SUMMARY = "CAN interface configuration"
LICENSE = "CLOSED"

SRC_URI = "file://can0.network"

S = "${WORKDIR}"

inherit systemd

RDEPENDS:${PN} += "systemd"

do_install() {
    install -d ${D}${systemd_unitdir}/network
    install -m 0644 ${WORKDIR}/can0.network ${D}${systemd_unitdir}/network/

    install -d ${D}${sysconfdir}/systemd/system/multi-user.target.wants
    ln -sf ${systemd_unitdir}/systemd-networkd.service \
           ${D}${sysconfdir}/systemd/system/multi-user.target.wants/systemd-networkd.service
}

FILES:${PN} += " \
    ${systemd_unitdir}/network/*.network \
    ${sysconfdir}/systemd/system/multi-user.target.wants/systemd-networkd.service \
"
