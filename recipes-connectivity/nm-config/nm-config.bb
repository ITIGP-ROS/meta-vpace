SUMMARY = "NetworkManager configuration"
LICENSE = "CLOSED"

SRC_URI = " \
    file://10-unmanaged-interfaces.conf \
    file://static-eth.nmconnection \
"

S = "${WORKDIR}"

RDEPENDS:${PN} += " \
    networkmanager \
"


do_install() {
    install -d ${D}${sysconfdir}/NetworkManager/conf.d
    install -m 0644 ${WORKDIR}/10-unmanaged-interfaces.conf ${D}${sysconfdir}/NetworkManager/conf.d/

    install -d ${D}${sysconfdir}/NetworkManager/system-connections
    install -m 0600 ${WORKDIR}/static-eth.nmconnection ${D}${sysconfdir}/NetworkManager/system-connections/
}

FILES:${PN} += " \
    ${sysconfdir}/NetworkManager/conf.d/*.conf \
    ${sysconfdir}/NetworkManager/system-connections/*.nmconnection \
"