DESCRIPTION = "V-PACE AI traffic sign detection bringup package"
MAINTAINER = "youhana <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake
inherit systemd

SRC_URI = "\
    git://github.com/ITIGP-ROS/ros2_ws_gp.git;protocol=https;branch=main \
    file://camera-sign-detect.service \
"
SRCREV = "248757936de8237a27d38f4bed64789ec27ef44b"

S = "${WORKDIR}/git/src/camera_sign_detect_bringup"

# S points into the git clone, so the file:// entry above needs its own path. Same
# arrangement as update-coordinator.bb, which mixes the two fetchers the same way.
UNPACKDIR ?= "${WORKDIR}"

SYSTEMD_SERVICE:${PN} = "camera-sign-detect.service"
# Nothing to enable: 99-vpace-camera.rules starts the unit when the camera appears. It
# must not be pulled in at boot, because with no camera attached there is no device for
# v4l2_camera to open.
SYSTEMD_AUTO_ENABLE = "disable"

ROS_BUILD_DEPENDS = " \
    ament-cmake-native \
    ament-index-python \
    launch \
    launch-ros \
    rclcpp \
    rclcpp-components \
    vision-msgs \
"

ROS_EXEC_DEPENDS = " \
    ament-index-python \
    launch \
    launch-ros \
    rclcpp \
    rclcpp-components \
    v4l2-camera \
    vision-msgs \
"

# Inference backend selection (build-time): default ONNX.
# TRT-only: PACKAGECONFIG:pn-camera-sign-detect-bringup = "trt"
PACKAGECONFIG ??= "onnx"
PACKAGECONFIG[onnx] = ""
PACKAGECONFIG[trt] = ""

# Bake the chosen backend into config/backend via CMake
EXTRA_OECMAKE += "${@bb.utils.contains('PACKAGECONFIG', 'trt', '-DCAMERA_SIGN_DETECT_BACKEND=trt', '-DCAMERA_SIGN_DETECT_BACKEND=onnx', d)}"

# Runtime dependency follows the backend (evaluated at finalize, after distro override)
DEPENDS = "${ROS_BUILD_DEPENDS}"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS} ${@bb.utils.contains('PACKAGECONFIG', 'trt', 'ros2-yolos-cpp-trt', 'ros2-yolos-cpp', d)}"

# 99-vpace-camera.rules is what starts camera-sign-detect.service, and its
# SYMLINK+="camera-front" is what gives the unit's BindsTo= a stable device name to bind
# to. Installed apart from this package the unit would never start; installed here
# without the unit the rule would name a service that does not exist. Both are already
# in orinivi-image.bb -- this makes the coupling explicit so it survives a slimmer image.
# camera_params.yaml also hardcodes /dev/camera-front, so the rule is required either way.
RDEPENDS:${PN} += "camera-udev-rules"

FILES_SOLIBSDEV = ""
# Package launch, params, config, and models directories
FILES:${PN} += " \
    ${ros_libdir}/*.so \
    ${ros_datadir}/${ROS_BPN}/launch \
    ${ros_datadir}/${ROS_BPN}/params \
    ${ros_datadir}/${ROS_BPN}/config \
    ${ros_datadir}/${ROS_BPN}/models \
    ${systemd_system_unitdir}/camera-sign-detect.service \
"
INSANE_SKIP:${PN} += "dev-so"

do_install:append() {
    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${UNPACKDIR}/camera-sign-detect.service \
        ${D}${systemd_system_unitdir}/camera-sign-detect.service
}