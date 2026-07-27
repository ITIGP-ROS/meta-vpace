SUMMARY = "SWUpdate public key for image verification"
LICENSE = "CLOSED"

SRC_URI = "file://swupdate.pem"

do_install() {
    install -d ${D}/usr/share/swupdate
    install -m 0644 ${WORKDIR}/swupdate.pem ${D}/usr/share/swupdate/swupdate.pem
}

FILES:${PN} = "/usr/share/swupdate/swupdate.pem"
