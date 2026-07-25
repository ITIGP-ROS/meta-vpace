DESCRIPTION = "ROS 2 Wrapper for YOLOs-CPP Inference Library"
MAINTAINER = "YOLOs-CPP Team <abdalrahman.m5959@gmail.com>"
LICENSE = "AGPL-3.0-only"
LIC_FILES_CHKSUM = "file://LICENSE;md5=eb1e647870add0502f8f010b19de32af"

inherit ros_distro_humble
inherit ros_ament_cmake
inherit cuda

SRC_URI = "git://github.com/Geekgineer/ros2_yolos_cpp.git;protocol=https;branch=main"
SRCREV = "${AUTOREV}"

S = "${WORKDIR}/git"

EXTRA_OECMAKE += "-DCMAKE_BUILD_TYPE=Release"

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

DEPENDS = "${ROS_BUILD_DEPENDS} patchelf-native"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS}"

do_configure[network] = "1"

do_compile:prepend() {
    export ROS_DISTRO="humble"
}

FILES_SOLIBSDEV = ""
FILES:${PN} += " \
    ${ros_libdir}/*.so \
    ${ros_datadir}/${ROS_BPN}/launch \
    ${ros_datadir}/${ROS_BPN}/config \
"

do_install:append() {
    if [ -d "${B}/onnxruntime/lib" ]; then
        install -d ${D}${ros_libdir}
        for f in ${B}/onnxruntime/lib/libonnxruntime.so*; do
            [ -e "$f" ] && install -m 0755 "$f" ${D}${ros_libdir}/
        done
    fi

    if [ -f "${D}${ros_libdir}/libros2_yolos_cpp_components.so" ]; then
        patchelf --set-rpath '$ORIGIN' ${D}${ros_libdir}/libros2_yolos_cpp_components.so
    fi
}

DEBUG_PREFIX_MAP = "-fdebug-prefix-map=${WORKDIR}=/usr/src/debug/${PN}-${PV}"
TARGET_CC_ARCH += "${DEBUG_PREFIX_MAP}"

INSANE_SKIP:${PN} += "already-stripped dev-so file-rdeps buildpaths"