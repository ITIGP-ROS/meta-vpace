SUMMARY = "ITI Qt6 IVI Application"
LICENSE = "CLOSED"

SRC_URI = " \
    git://github.com/ITIGP-ROS/IVI.git;protocol=https;branch=main \
    file://ivi-app.service \
    file://ivi-tmpfiles.conf \
    file://dds-udp-only.xml \
"

SRCREV = "${AUTOREV}"
PV = "1.0+git${SRCPV}"

S = "${WORKDIR}/git"

UNPACKDIR ?= "${WORKDIR}"

inherit qt6-cmake systemd
inherit ros_distro_humble
inherit ros_ament_cmake

# ros_ament_cmake installs to /opt/ros/humble by default — keep the app in
# /usr so the service file and asset paths stay unchanged (appended -D wins)
EXTRA_OECMAKE:append = " -DCMAKE_INSTALL_PREFIX=${prefix}"

# --- ROS2 dependencies ---
ROS_BUILD_DEPENDS = " \
    rclcpp \
    geometry-msgs \
    std-msgs \
    sensor-msgs \
    object-detection-msgs \
"

ROS_BUILDTOOL_DEPENDS = " \
    ament-cmake-native \
"

ROS_EXEC_DEPENDS = " \
    rclcpp \
    geometry-msgs \
    std-msgs \
    sensor-msgs \
    object-detection-msgs \
"

# Consuming rclcpp/msgs at configure time pulls the target ament_cmake*
# packages: their installed CMake configs call find_package(ament_cmake_*)
# REQUIRED for the full set that the ament_cmake umbrella re-exports.
# meta-ros only propagates these as -native variants, so the target ones must
# be staged in the recipe sysroot explicitly (same flattening superflore does).
DEPENDS += " \
    qtbase \
    qtdeclarative \
    qtdeclarative-native \
    qtmultimedia \
    qtquick3d \
    qtquick3d-native \
    pulseaudio \
    vosk \
    ${ROS_BUILD_DEPENDS} \
    ${ROS_BUILDTOOL_DEPENDS} \
    ament-cmake \
    ament-cmake-core \
    ament-cmake-export-definitions \
    ament-cmake-export-dependencies \
    ament-cmake-export-include-directories \
    ament-cmake-export-interfaces \
    ament-cmake-export-libraries \
    ament-cmake-export-link-flags \
    ament-cmake-export-targets \
    ament-cmake-gen-version-h \
    ament-cmake-include-directories \
    ament-cmake-libraries \
    ament-cmake-python \
    ament-cmake-target-dependencies \
    ament-cmake-test \
    ament-cmake-version \
"

# --- Qt6 IVI ---
RDEPENDS:${PN} += " \
   qtbase \
    qtdeclarative \
    qtmultimedia \
    qt5compat \
    qtsvg \
    qtimageformats \
    qtdeclarative-qmlplugins \
    qtquick3d \
    qtquick3d-qmlplugins \
"

# --- ROS2 runtime ---
RDEPENDS:${PN} += "${ROS_EXEC_DEPENDS}"

# -- fonts ---
RDEPENDS:${PN} += " \
    ttf-noto-emoji-color \
"

# -- speech  ---
RDEPENDS:${PN} += " vosk "

# --- Audio / Bluetooth  ---
# module-loopback is required for phone audio and is not pulled in by any of the
# bluez5 packages. A phone is an A2DP *source*, so pulseaudio exposes it as
# bluez_source.<MAC>.a2dp_source; module-bluetooth-policy routes that to the
# speakers by loading module-loopback. Without it on disk, policy fails and falls
# back to setting the card profile to "off" — the phone pairs, AVRCP metadata and
# transport controls all work, and there is no sound.
RDEPENDS:${PN} += " \
    pulseaudio \
    pulseaudio-server \
    pulseaudio-misc \
    pulseaudio-module-dbus-protocol \
    pulseaudio-module-bluetooth-discover \
    pulseaudio-module-bluetooth-policy \
    pulseaudio-module-bluez5-device \
    pulseaudio-module-bluez5-discover \
    pulseaudio-module-loopback \
    bluez5 \
"

# --- GStreamer (software + NVIDIA hw) ---
RDEPENDS:${PN} += " \
    gstreamer1.0 \
    gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad \
    gstreamer1.0-libav \
    gstreamer1.0-plugins-nvvideo4linux2 \
    gstreamer1.0-plugins-nvvidconv \
    liberation-fonts \
"

do_install:append() {
    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${UNPACKDIR}/ivi-app.service ${D}${systemd_system_unitdir}/

    # Fast DDS transport profile: UDPv4 only, no shared memory.
    #
    # Load-bearing, not tuning. appIVI runs as `weston` while the ROS
    # publishers run as `root`, and Fast DDS shared-memory ports are created
    # 0644 by their owning user, so the cross-user SHM channel silently never
    # forms -- discovery matches, no data ever arrives. ivi-app.service points
    # FASTRTPS_DEFAULT_PROFILES_FILE at this; the file itself carries the full
    # rationale, the measurements, and when to revisit the tradeoff.
    #
    # 0644 so the weston-owned app can read it at startup.
    install -d ${D}${sysconfdir}
    install -m 0644 ${UNPACKDIR}/dds-udp-only.xml ${D}${sysconfdir}/

    # Install Vosk model from git repo assets
    install -d ${D}/usr/assets/models/vosk
    cp -r ${S}/assets/models/vosk/* ${D}/usr/assets/models/vosk/


    # Install an empty dir for persistent data (media)
    install -d -m 0755 ${D}/var/lib/ivi/media

    # Persistent media dir must be writable by the weston user (uid 1000):
    # set ownership at boot via tmpfiles (chown at install time would leak
    # host uid/gid into the package and break do_package).
    install -d ${D}${nonarch_libdir}/tmpfiles.d
    install -m 0644 ${UNPACKDIR}/ivi-tmpfiles.conf ${D}${nonarch_libdir}/tmpfiles.d/
}
FILES:${PN} += " \
    /usr/assets/models/vosk \
    ${systemd_system_unitdir}/ivi-app.service \
    /var/lib/ivi/media \
    ${nonarch_libdir}/tmpfiles.d/ivi-tmpfiles.conf \
    ${sysconfdir}/dds-udp-only.xml \
"

SYSTEMD_AUTO_ENABLE = "enable"
SYSTEMD_SERVICE:${PN} = "ivi-app.service"

# for swupdate
do_deploy() {
    tar czf ${DEPLOY_DIR_IMAGE}/ivi-app-${MACHINE}.tar.gz \
        -C ${D} .
}
addtask deploy after do_install before do_build