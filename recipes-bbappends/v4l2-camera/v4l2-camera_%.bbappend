FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

inherit cuda

# Upstream v4l2_camera never calls VIDIOC_S_PARM, so it streams at the format's
# default rate (30fps) regardless of camera_params.yaml -- true of every release,
# nothing to upgrade to. Fails silently since rclcpp discards unknown YAML keys,
# so this went unnoticed while driving 15x the intended TensorRT inference rate.
# Setting the rate externally doesn't work either (VIDIOC_S_FMT resets it). See
# the patch header for the full rationale.
SRC_URI:append = " file://0001-v4l2_camera-add-time_per_frame-parameter.patch"
