DESCRIPTION = "ROS 2 Wrapper for YOLOs-CPP Inference Library (TensorRT backend)"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "AGPL-3.0-only"
LIC_FILES_CHKSUM = "file://LICENSE;md5=eb1e647870add0502f8f010b19de32af"

inherit ros_distro_humble
inherit ros_ament_cmake
inherit cuda

SRC_URI = "git://github.com/YouhanaBeshay/ros2_yolos_cpp_trt.git;protocol=https;branch=dev"
SRCREV = "209e45af5274813a9da233dafa50f0eea4a49825"

S = "${WORKDIR}/git"

CUDA_ARCHITECTURES = "87"
EXTRA_OECMAKE += "-DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF"

ROS_BUILD_DEPENDS = " \
    ament-cmake-native \
    rosidl-default-generators-native \
    rclcpp \
    rclcpp-lifecycle \
    rclcpp-components \
    image-transport \
    cv-bridge \
    sensor-msgs \
    vision-msgs \
    std-msgs \
    geometry-msgs \
    lifecycle-msgs \
    opencv \
"

ROS_EXEC_DEPENDS = " \
    rclcpp \
    rclcpp-lifecycle \
    rclcpp-components \
    image-transport \
    cv-bridge \
    sensor-msgs \
    vision-msgs \
    std-msgs \
    geometry-msgs \
    lifecycle-msgs \
    rosidl-default-runtime \
"

DEPENDS = "${ROS_BUILD_DEPENDS} tensorrt-core"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS} tensorrt-core"

do_compile:prepend() {
    export ROS_DISTRO="humble"
}

FILES_SOLIBSDEV = ""
FILES:${PN} += " \
    ${ros_libdir}/*.so \
    ${ros_datadir}/${ROS_BPN}/launch \
    ${ros_datadir}/${ROS_BPN}/config \
"

DEBUG_PREFIX_MAP = "-fdebug-prefix-map=${WORKDIR}=/usr/src/debug/${PN}-${PV}"
TARGET_CC_ARCH += "${DEBUG_PREFIX_MAP}"

INSANE_SKIP:${PN} += "already-stripped dev-so buildpaths"
