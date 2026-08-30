# CUDA is only reached transitively here, via find_package(cv_bridge) ->
# OpenCVConfig.cmake -> find_package(CUDA), since our OpenCV is a tegra/CUDA build.
# Same reason as the cv-bridge, v4l2-camera and nav2-waypoint-follower bbappends.

inherit cuda
