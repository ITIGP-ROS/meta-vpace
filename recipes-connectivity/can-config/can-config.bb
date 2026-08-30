SUMMARY = "CAN interface configuration"
LICENSE = "CLOSED"

SRC_URI = "file://can0.network \
           file://10-can0.link"

S = "${WORKDIR}"

inherit systemd

RDEPENDS:${PN} += "systemd"

do_install() {
    install -d ${D}${systemd_unitdir}/network
    install -m 0644 ${WORKDIR}/can0.network ${D}${systemd_unitdir}/network/
    # .link and .network live in the same directory but are consumed by different
    # components: udev applies the .link at device appearance, networkd the .network.
    install -m 0644 ${WORKDIR}/10-can0.link ${D}${systemd_unitdir}/network/

    install -d ${D}${sysconfdir}/systemd/system/multi-user.target.wants
    ln -sf ${systemd_unitdir}/systemd-networkd.service \
           ${D}${sysconfdir}/systemd/system/multi-user.target.wants/systemd-networkd.service
}

FILES:${PN} += " \
    ${systemd_unitdir}/network/*.network \
    ${systemd_unitdir}/network/*.link \
    ${sysconfdir}/systemd/system/multi-user.target.wants/systemd-networkd.service \
"
