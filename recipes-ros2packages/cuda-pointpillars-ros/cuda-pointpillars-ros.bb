DESCRIPTION = "TensorRT PointPillars 3D LiDAR detector with in-process AB3DMOT tracking"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"

# Descended from NVIDIA's CUDA-PointPillars; the decoder, pillar scatter and
# preprocessing all carry the 2021 NVIDIA SPDX headers. There is no LICENSE file
# in the package, so the checksum is taken over one of those headers -- they are
# byte-identical across the ten files that carry them.
LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "file://src/pointpillar.cpp;beginline=1;endline=16;md5=6b69e9af71e900c3192611d818c2536f"

inherit ros_distro_humble
inherit ros_ament_cmake
inherit cuda

require conf/include/ros2-lidar-perception.inc

S = "${WORKDIR}/git/src/cuda_pointpillars_ros"

# TensorRT only exists on tegra, and the package cannot be built without it.
COMPATIBLE_MACHINE = "(tegra)"

# Orin NX is sm_87. This feeds CMAKE_CUDA_ARCHITECTURES via cuda.bbclass; GPU_SMS
# below is the same number for the package's own -gencode list, which predates
# the CMake CUDA language support and is what actually reaches nvcc here.
CUDA_ARCHITECTURES = "87"

# ── Where TensorRT actually lives in a cross build ───────────────────────────
# The package defaults to /usr/include/aarch64-linux-gnu and
# /usr/lib/aarch64-linux-gnu, which is correct for JetPack's .debs on a running
# board and wrong here: meta-tegra's tensorrt-core and tensorrt-plugins-prebuilt
# both install to ${includedir}/${libdir} with no multiarch component. Left
# unset, the compiler would be pointed at the BUILD HOST's /usr/include -- which
# on an x86_64 builder either fails to find NvInfer.h or, worse, finds a
# different version of it.
#
# cuda.bbclass covers the CUDA toolkit paths but says nothing about TensorRT;
# these two are the part it does not reach.
EXTRA_OECMAKE += " \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_TESTING=OFF \
    -DGPU_SMS=87 \
    -DTENSORRT_INCLUDE_DIRS=${STAGING_INCDIR} \
    -DTENSORRT_LIBRARY_DIRS=${STAGING_LIBDIR} \
"

ROS_BUILD_DEPENDS = " \
    ament-cmake-native \
    rclcpp \
    sensor-msgs \
    visualization-msgs \
    geometry-msgs \
    object-detection-msgs \
    lidar-tracking \
"

ROS_EXEC_DEPENDS = " \
    rclcpp \
    sensor-msgs \
    visualization-msgs \
    geometry-msgs \
    object-detection-msgs \
    lidar-tracking \
"

# tensorrt-core carries libnvinfer. tensorrt-plugins-prebuilt carries
# libnvonnxparser, which this links by its UNVERSIONED name -- and per the note
# in orinivi-image.bb the unversioned .so symlink lands in the -dev package, so
# it must be staged at build time even though only the SONAME matters at
# runtime. Both are needed: the ONNX parser is not part of tensorrt-core.
DEPENDS = "${ROS_BUILD_DEPENDS} tensorrt-core tensorrt-plugins-prebuilt"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS} tensorrt-core tensorrt-plugins-prebuilt"

do_compile:prepend() {
    export ROS_DISTRO="humble"
}

# pc_process installs to lib/${PROJECT_NAME}, the ament convention for node
# executables; the .onnx installs to share/${PROJECT_NAME}/model.
#
# NOTE ON THE MODEL: only the .onnx ships. TensorRT engines must be built on the
# target and are bound to an exact TRT version + GPU arch, so a prebuilt one
# would be useless here -- see the TensorRT section of orinivi-image.bb. The
# node builds and caches its own engine on first run. Where that cache lands is
# handled by lidar-perception-bringup, not here: the default location is beside
# the .onnx under ${ros_datadir}, which does not survive an A/B OTA.
FILES:${PN} += " \
    ${ros_libdir}/${ROS_BPN} \
    ${ros_datadir}/${ROS_BPN}/model \
"

DEBUG_PREFIX_MAP = "-fdebug-prefix-map=${WORKDIR}=/usr/src/debug/${PN}-${PV}"
TARGET_CC_ARCH += "${DEBUG_PREFIX_MAP}"

INSANE_SKIP:${PN} += "already-stripped dev-so buildpaths"
