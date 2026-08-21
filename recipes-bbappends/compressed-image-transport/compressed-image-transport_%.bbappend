# since our OpenCV lib is a tegra one (uses CUDA)
# sp we need to inherit the cuda class to make sure the proper flags are set for the build
#
# Reached transitively, not directly: this recipe never touches CUDA itself. It calls
# find_package(cv_bridge), whose cv_bridgeConfig.cmake pulls OpenCVConfig.cmake, which
# does find_package(CUDA EXACT 12.6) -- so do_configure fails with
# "Could NOT find CUDA (missing: CUDA_TOOLKIT_ROOT_DIR ...)" without this.
# Same reason as the cv-bridge, v4l2-camera and nav2-waypoint-follower bbappends.

inherit cuda
