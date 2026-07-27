do_compile:append() {
    sed -i '/^\tloglevel = 3;/a\\tpublic-key-file = "\/usr\/share\/swupdate\/swupdate.pem";' ${B}/swupdate.cfg.in
}
