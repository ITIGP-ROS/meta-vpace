SUMMARY = "Format and mount the UDA data partition at /data"
LICENSE = "CLOSED"

inherit systemd

SRC_URI = " \
    file://mount-data-partition.sh \
    file://data-partition-mount.service \
"

S = "${WORKDIR}"

do_install() {
    install -d ${D}${sbindir} ${D}${systemd_system_unitdir}
    install -m 0755 mount-data-partition.sh ${D}${sbindir}/
    install -m 0644 data-partition-mount.service ${D}${systemd_system_unitdir}/
}

SYSTEMD_SERVICE:${PN} = "data-partition-mount.service"
