FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

SRC_URI += "file://weston.ini"

do_install:append() {
    install -m 0644 ${WORKDIR}/weston.ini ${D}${sysconfdir}/xdg/weston/weston.ini
}