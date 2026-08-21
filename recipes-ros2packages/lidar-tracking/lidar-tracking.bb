DESCRIPTION = "C++ AB3DMOT 3D multi-object tracker used by the LiDAR perception pipeline"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"

# NOT MIT, whatever package.xml claims.
#
# src/lidar_tracking/LICENSE is the AB3DMOT software licence agreement:
# "ACADEMIC OR NON-PROFIT ORGANIZATION NONCOMMERCIAL RESEARCH USE ONLY". The
# package.xml says MIT, and the two cannot both be true. Declaring MIT here
# would put MIT into the image licence manifest -- i.e. it would make the
# product's stated legal position a copy of the wrong one of two contradictory
# files, silently.
#
# So this declares what is actually in the tree and lets the manifest tell the
# truth. Nothing filters on it today (the distro sets no INCOMPATIBLE_LICENSE),
# so this does not block a build; it makes the problem visible instead of
# burying it. Resolve it upstream by either
#
#   * confirming the C++ port is an independent implementation of the published
#     algorithm, deleting the inherited LICENSE file, and keeping MIT; or
#   * obtaining a commercial grant from the AB3DMOT authors; or
#   * accepting the non-commercial restriction on the shipped product.
#
# Once that is settled, change LICENSE/LIC_FILES_CHKSUM here to match.
LICENSE = "AB3DMOT-academic"
LIC_FILES_CHKSUM = "file://LICENSE;md5=766784d0874f614f972829e741f07f7a"
NO_GENERIC_LICENSE[AB3DMOT-academic] = "LICENSE"

inherit ros_distro_humble
inherit ros_ament_cmake

require conf/include/ros2-lidar-perception.inc

S = "${WORKDIR}/git/src/lidar_tracking"

# Header-only consumers still link the shared library, so this must build and
# stage like any other ament library. Eigen is the only third-party dependency
# -- the port deliberately avoids PCL, OpenCV and the numpy-shaped helpers the
# Python original leaned on.
ROS_BUILD_DEPENDS = " \
    ament-cmake-native \
    libeigen \
"

ROS_EXEC_DEPENDS = ""

# BUILD_TESTING pulls in ament_cmake_gtest and nlohmann_json for the golden
# vector tests. Those goldens pin this port against the Python AB3DMOT
# reference, which is exactly what you want to run on a workstation and exactly
# what you do not want to cross-build into an image.
EXTRA_OECMAKE += "-DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF"

DEPENDS = "${ROS_BUILD_DEPENDS}"
RDEPENDS:${PN} = "${ROS_EXEC_DEPENDS}"

do_compile:prepend() {
    export ROS_DISTRO="humble"
}

# liblidar_tracking.so is unversioned, so the default FILES_SOLIBSDEV would put
# it in -dev and leave the runtime package without the library it exists to
# ship. Same arrangement as ros2-yolos-cpp.bb.
FILES_SOLIBSDEV = ""
FILES:${PN} += "${ros_libdir}/*.so"

INSANE_SKIP:${PN} += "dev-so"
