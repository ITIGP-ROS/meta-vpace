DESCRIPTION = "V-PACE AI traffic sign detection bringup package"
MAINTAINER = "youhana <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit ros_distro_humble
inherit ros_ament_cmake

SRC_URI = "git://github.com/ITIGP-ROS/ros2_ws_gp.git;protocol=https;branch=main"
SRCREV = "070b54c21527248e2f80b304d7770e4b4db654ba"

S = "${WORKDIR}/git/src/camera_sign_detect_bringup"

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

FILES_SOLIBSDEV = ""
# Package launch, params, config, and models directories
FILES:${PN} += " \
    ${ros_libdir}/*.so \
    ${ros_datadir}/${ROS_BPN}/launch \
    ${ros_datadir}/${ROS_BPN}/params \
    ${ros_datadir}/${ROS_BPN}/config \
    ${ros_datadir}/${ROS_BPN}/models \
"
INSANE_SKIP:${PN} += "dev-so"