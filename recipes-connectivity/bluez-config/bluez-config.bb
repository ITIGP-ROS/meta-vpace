SUMMARY = "Bluetooth adapter policy (bluetoothd main.conf)"
LICENSE = "CLOSED"

SRC_URI = " \
    file://main.conf \
"

S = "${WORKDIR}"

RDEPENDS:${PN} += " \
    bluez5 \
"

# bluez5 (poky, bluez5.inc) installs ONLY network.conf and input.conf into
# ${sysconfdir}/bluetooth -- it has never shipped a main.conf, which is precisely why
# this recipe exists. Checked against bluez5 5.72; if a future uprev starts packaging
# ${sysconfdir}/bluetooth/main.conf the two packages will conflict on that path at
# rootfs time. The fix then is to convert this into a bluez5_%.bbappend that overwrites
# the file, NOT a second package shipping the same path.
do_install() {
    install -d ${D}${sysconfdir}/bluetooth
    install -m 0644 ${WORKDIR}/main.conf ${D}${sysconfdir}/bluetooth/
}

FILES:${PN} += " \
    ${sysconfdir}/bluetooth/main.conf \
"
