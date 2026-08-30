SUMMARY = "NetworkManager configuration"
LICENSE = "CLOSED"

SRC_URI = " \
    file://10-unmanaged-interfaces.conf \
    file://10-eth-unmanaged.network \
    file://20-keyfile-path.conf \
    file://30-wifi-powersave.conf \
    file://nm-state-on-data.conf \
    file://static-eth.nmconnection \
"

S = "${WORKDIR}"

RDEPENDS:${PN} += " \
    networkmanager \
"

# /data is a mount point -- its directories are created at boot by
# mount-data-partition.sh, not staged here.
RDEPENDS:${PN} += " \
    data-partition-mount \
"

do_install() {
    install -d ${D}${sysconfdir}/NetworkManager/conf.d
    install -m 0644 ${WORKDIR}/10-unmanaged-interfaces.conf ${D}${sysconfdir}/NetworkManager/conf.d/
    install -m 0644 ${WORKDIR}/20-keyfile-path.conf ${D}${sysconfdir}/NetworkManager/conf.d/
    install -m 0644 ${WORKDIR}/30-wifi-powersave.conf ${D}${sysconfdir}/NetworkManager/conf.d/

    # The other half of the division of labour: 10-unmanaged-interfaces.conf keeps
    # NetworkManager off can0, this keeps systemd-networkd off ethernet. Goes in
    # networkd's directory, not NetworkManager's -- it is a networkd config file.
    install -d ${D}${systemd_unitdir}/network
    install -m 0644 ${WORKDIR}/10-eth-unmanaged.network ${D}${systemd_unitdir}/network/

    # static-eth goes in the read-only profile dir, not /etc: 20-keyfile-path.conf
    # repoints the writable store at /data, so a profile left in /etc would
    # silently stop being read. 0600 since NetworkManager ignores looser keyfiles.
    install -d ${D}${nonarch_libdir}/NetworkManager/system-connections
    install -m 0600 ${WORKDIR}/static-eth.nmconnection \
        ${D}${nonarch_libdir}/NetworkManager/system-connections/

    # Bind-mounts /var/lib/NetworkManager onto /data so `timestamps` and
    # `seen-bssids` survive a flash too. See the file for why its leading "-"
    # must stay.
    install -d ${D}${systemd_system_unitdir}/NetworkManager.service.d
    install -m 0644 ${WORKDIR}/nm-state-on-data.conf \
        ${D}${systemd_system_unitdir}/NetworkManager.service.d/
}

FILES:${PN} += " \
    ${sysconfdir}/NetworkManager/conf.d/*.conf \
    ${nonarch_libdir}/NetworkManager/system-connections/*.nmconnection \
    ${systemd_system_unitdir}/NetworkManager.service.d/*.conf \
    ${systemd_unitdir}/network/10-eth-unmanaged.network \
"
