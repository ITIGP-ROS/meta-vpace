FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
SRC_URI += "file://0001-genconfig-create-complete-markers-on-fresh-flash.patch"

do_compile:append() {
    sed -i '/^\tloglevel = 3;/a\\tpublic-key-file = "\/usr\/share\/swupdate\/swupdate.pem";' ${B}/swupdate.cfg.in
}
