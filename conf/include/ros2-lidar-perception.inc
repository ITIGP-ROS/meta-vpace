# Shared fetch definition for the five ROS 2 packages in ros2-lidar-perception.
# Centralized so all five always build from the same revision -- a mismatch
# here wouldn't fail the build, just produce a node that disagrees with its own
# message definitions at runtime. Each recipe sets its own S under src/.

ROS2_LIDAR_PERCEPTION_REPO   ?= "github.com/ITIGP-ROS/ros2-lidar-perception.git"
ROS2_LIDAR_PERCEPTION_BRANCH ?= "main"

SRC_URI = "git://${ROS2_LIDAR_PERCEPTION_REPO};protocol=https;branch=${ROS2_LIDAR_PERCEPTION_BRANCH}"

# TensorRT PointPillars + C++ AB3DMOT, plus the CMake cross-compile fix that
# lets cuda-pointpillars-ros.bb redirect TENSORRT_INCLUDE_DIRS/LIBRARY_DIRS and
# CUDA_TOOLKIT_ROOT_DIR into the Yocto sysroot instead of the JetPack .deb paths.
SRCREV = "002f3c5815bd21a38fa8374b5ac4ab58341b8d3e"

# One PV for all five, since they ship as one unit. Bump on behaviour changes,
# not on every SRCREV move.
PV = "0.1.0+git"
