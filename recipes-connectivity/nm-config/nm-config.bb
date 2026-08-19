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

# The learned-WiFi store and NetworkManager's state directory both live under
# /data, and the directories are created at boot by mount-data-partition.sh --
# /data is a mount point, so nothing staged into it at image build time would
# ever be visible.
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

    # static-eth goes in the READ-ONLY profile directory, not /etc.
    #
    # 20-keyfile-path.conf repoints the writable store at /data, and `path`
    # REPLACES /etc/NetworkManager/system-connections rather than adding to it
    # -- a profile left in /etc after that would silently stop being read, and
    # the LiDAR link would go dead with no error. NetworkManager always reads
    # ${nonarch_libdir}/NetworkManager/system-connections regardless of `path`,
    # and it is the conventional home for a profile that ships with the image
    # instead of being learned on the box.
    #
    # 0600 is required: NetworkManager ignores keyfiles that are readable or
    # writable by any user or group other than root.
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
