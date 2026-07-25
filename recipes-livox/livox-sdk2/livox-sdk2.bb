DESCRIPTION = "Livox SDK2 - LiDAR communication library"
MAINTAINER = "Youhana Beshay <youhanabeshay@gmail.com>"
LICENSE = "CLOSED"

inherit cmake

SRC_URI = " \
    git://github.com/Livox-SDK/Livox-SDK2.git;protocol=https;branch=master \
    file://0001-fix-qa-so.patch \
"

SRCREV = "f5d9375f84efe2b15bc0a052d3e18482ed13adf4"  

S = "${WORKDIR}/git"

# needed for std::uint8_t, std::uint16_t, std::uint32_t, std::uint64_t
CXXFLAGS += "-include cstdint"

# Install to standard paths so livox_ros_driver2 can find it
EXTRA_OECMAKE += "-DCMAKE_INSTALL_PREFIX=${prefix}"

