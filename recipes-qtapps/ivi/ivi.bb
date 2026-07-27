SUMMARY = "ITI Qt6 IVI Application"
LICENSE = "CLOSED"

SRC_URI = " \
    git://github.com/ITIGP-ROS/IVI.git;protocol=https;branch=main \
    file://ivi-app.service \
"

SRCREV = "${AUTOREV}"
PV = "1.0+git${SRCPV}"

S = "${WORKDIR}/git"

inherit qt6-cmake systemd

DEPENDS += " \
    qtbase \
    qtdeclarative \
    qtdeclarative-native \
    qtmultimedia \
    pulseaudio \
    vosk \
"

# --- Qt6 IVI ---
RDEPENDS:${PN} += " \
   qtbase \
    qtdeclarative \
    qtmultimedia \
    qt5compat \
    qtsvg \
    qtimageformats \
"


# -- speech  ---
RDEPENDS:${PN} += " vosk "

# --- Audio / Bluetooth  ---
RDEPENDS:${PN} += " \
    pulseaudio \
    pulseaudio-server \
    pulseaudio-misc \
    pulseaudio-module-dbus-protocol \
    pulseaudio-module-bluetooth-discover \
    pulseaudio-module-bluetooth-policy \
    pulseaudio-module-bluez5-device \
    pulseaudio-module-bluez5-discover \
    bluez5 \
"

# --- GStreamer (software + NVIDIA hw) ---
RDEPENDS:${PN} += " \
    gstreamer1.0 \
    gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad \
    gstreamer1.0-plugins-nvvideo4linux2 \
    gstreamer1.0-plugins-nvvidconv \
    liberation-fonts \
"

do_install:append() {
    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${WORKDIR}/ivi-app.service ${D}${systemd_system_unitdir}/

    # Install Vosk model from git repo assets
    install -d ${D}/usr/assets/models/vosk
    cp -r ${S}/assets/models/vosk/* ${D}/usr/assets/models/vosk/
}
FILES:${PN} += " \
    /usr/assets/models/vosk \
    ${systemd_system_unitdir}/ivi-app.service \
"

SYSTEMD_AUTO_ENABLE = "enable"
SYSTEMD_SERVICE:${PN} = "ivi-app.service"

# for swupdate
do_deploy() {
    tar czf ${DEPLOY_DIR_IMAGE}/ivi-app-${MACHINE}.tar.gz \
        -C ${D} .
}
addtask deploy after do_install before do_build