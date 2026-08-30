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

# The package's default TensorRT paths assume JetPack's multiarch .deb layout;
# meta-tegra installs to plain ${includedir}/${libdir}. Left unset, an x86_64
# build host would pick up its own (wrong or missing) NvInfer.h. cuda.bbclass
# covers CUDA toolkit paths but not TensorRT, hence this.
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

# tensorrt-core carries libnvinfer; tensorrt-plugins-prebuilt carries libnvonnxparser,
# linked by its unversioned name so the -dev package (which holds that symlink) is
# needed at build time even though only the SONAME matters at runtime.
DEPENDS = "${ROS_BUILD_DEPENDS} tensorrt-core tensorrt-plugins-prebuilt"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS} tensorrt-core tensorrt-plugins-prebuilt"

do_compile:prepend() {
    export ROS_DISTRO="humble"
}

# Only the .onnx ships -- TensorRT engines are bound to an exact TRT version and
# GPU arch, so a prebuilt one would be useless. The node builds and caches its own
# on first run; where that cache lands is handled by lidar-perception-bringup.
FILES:${PN} += " \
    ${ros_libdir}/${ROS_BPN} \
    ${ros_datadir}/${ROS_BPN}/model \
"

DEBUG_PREFIX_MAP = "-fdebug-prefix-map=${WORKDIR}=/usr/src/debug/${PN}-${PV}"
TARGET_CC_ARCH += "${DEBUG_PREFIX_MAP}"

INSANE_SKIP:${PN} += "already-stripped dev-so buildpaths"
