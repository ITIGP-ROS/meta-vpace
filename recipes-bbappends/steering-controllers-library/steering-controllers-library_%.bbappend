FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# Humble's SteeringOdometry shares one wheel_track_ between traction and steering
# axles, only exact when front/rear track match (ours don't: 0.12m vs 0.18179m).
# Fixed upstream on kilted but that depends on newer APIs Humble lacks, so this
# backports just the track-width split. See the patch header for the derivation.
SRC_URI:append = " file://0001-steering_odometry-split-steering-and-traction-track.patch"
