FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# Upstream (Humble) shares one wheel_track_ between the traction-axle differential-speed
# formula and the steering-axle angle-splitting formula in SteeringOdometry. That is only
# exact when front and rear track are equal, which ours are not (front 0.12 m, rear
# 0.18179 m per ackermann_bringup/config/controllers.yaml). Already fixed upstream on the
# kilted branch by splitting into wheel_track_steering_/wheel_track_traction_, but that
# rework depends on newer hardware_interface handle APIs not present in Humble, so it can't
# be cherry-picked as-is. This backports just the track-width split onto Humble's existing
# API. See the patch header for the full derivation and the companion patch on
# ackermann-steering-controller for the call site that supplies both tracks.
SRC_URI:append = " file://0001-steering_odometry-split-steering-and-traction-track.patch"
