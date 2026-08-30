SUMMARY = "Bluetooth adapter policy (bluetoothd main.conf)"
LICENSE = "CLOSED"

SRC_URI = " \
    file://main.conf \
"

S = "${WORKDIR}"

RDEPENDS:${PN} += " \
    bluez5 \
"

# bluez5 (poky) never ships main.conf, which is why this recipe exists (checked
# against 5.72). If a future uprev starts packaging it, convert this to a
# bluez5_%.bbappend instead -- two packages shipping the same path will conflict.
do_install() {
    install -d ${D}${sysconfdir}/bluetooth
    install -m 0644 ${WORKDIR}/main.conf ${D}${sysconfdir}/bluetooth/
}

FILES:${PN} += " \
    ${sysconfdir}/bluetooth/main.conf \
"
