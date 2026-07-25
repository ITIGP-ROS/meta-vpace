SUMMARY = "Static Ethernet configuration"
LICENSE = "CLOSED"


SRC_URI = "file://10-static-eth.network"

S = "${WORKDIR}"

inherit systemd

# Pull in the daemon that reads .network files
RDEPENDS:${PN} += "systemd"

do_install() {
    # 1. Install the network config
    install -d ${D}${systemd_unitdir}/network
    install -m 0644 ${WORKDIR}/10-static-eth.network ${D}${systemd_unitdir}/network/

    # 2. Enable systemd-networkd on boot
    install -d ${D}${sysconfdir}/systemd/system/multi-user.target.wants
    ln -sf ${systemd_unitdir}/systemd-networkd.service \
           ${D}${sysconfdir}/systemd/system/multi-user.target.wants/systemd-networkd.service
}

FILES:${PN} += " \
    ${systemd_unitdir}/network/*.network \
    ${sysconfdir}/systemd/system/multi-user.target.wants/systemd-networkd.service \
"