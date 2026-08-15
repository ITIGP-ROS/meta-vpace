FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

inherit cuda

# Upstream v4l2_camera cannot set the capture frame rate: it applies a format and
# streams at whatever interval that format defaults to (30 fps on our Brio 100), and
# never calls VIDIOC_S_PARM. This is true of every release -- 0.6.2 (humble), 0.7.1
# (jazzy), 0.8.0 (rolling) and master -- so there is nothing to upgrade to.
#
# It fails silently, which is what made it expensive: rclcpp keeps unknown YAML keys
# as parameter overrides and discards them without warning, so camera_params.yaml
# requested 2 fps while the camera actually ran at 30, driving 15x the intended
# TensorRT inference and 18.4 MB/s of discarded YUYV across a USB 2.0 bus shared with
# the WiFi dongle.
#
# Setting the rate outside the node does not work either: VIDIOC_S_FMT resets the
# frame interval, so the node undoes any external S_PARM when it applies its format
# (measured on target -- 1/5 set beforehand reads back as 1/30 once the node starts).
#
# See the patch header for the full rationale.
SRC_URI:append = " file://0001-v4l2_camera-add-time_per_frame-parameter.patch"
