SUMMARY = "Stable /dev/gamepad name and start edge for the manual override"
DESCRIPTION = "udev rule that names any connected gamepad /dev/gamepad and starts \
ackermann-joystick.service when it appears, over USB or Bluetooth. The override unit \
BindsTo the resulting device unit, so it also stops on unplug."
LICENSE = "CLOSED"

SRC_URI = "file://99-vpace-gamepad.rules"

S = "${WORKDIR}"

RDEPENDS:${PN} += "udev"

# The rule names a service that ackermann-bringup ships. Installed apart from it the unit
# would never start; installed without the rule the unit could only be started by hand.
# Same coupling camera-udev-rules has with camera-sign-detect-bringup.
RDEPENDS:${PN} += "ackermann-bringup"

do_install() {
    install -d ${D}${sysconfdir}/udev/rules.d
    install -m 0644 ${WORKDIR}/99-vpace-gamepad.rules ${D}${sysconfdir}/udev/rules.d/
}

FILES:${PN} += "${sysconfdir}/udev/rules.d/99-vpace-gamepad.rules"
