SUMMARY = "Stable /dev/camera-front symlink for the sign-detection camera"
DESCRIPTION = "udev rule giving the front camera a fixed device name, so that \
camera_params.yaml does not have to hardcode an unstable /dev/videoN."
LICENSE = "CLOSED"

SRC_URI = "file://99-vpace-camera.rules"

S = "${WORKDIR}"

RDEPENDS:${PN} += "udev"

do_install() {
    install -d ${D}${sysconfdir}/udev/rules.d
    install -m 0644 ${WORKDIR}/99-vpace-camera.rules ${D}${sysconfdir}/udev/rules.d/
}

FILES:${PN} += "${sysconfdir}/udev/rules.d/99-vpace-camera.rules"
