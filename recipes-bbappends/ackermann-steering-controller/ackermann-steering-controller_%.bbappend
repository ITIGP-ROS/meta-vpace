FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# Companion to the steering-controllers-library patch: configure_odometry() only ever
# passed one track width, so every steering-angle calculation used the traction axle's
# track instead of the steering axle's. See that patch's header for the derivation.
SRC_URI:append = " file://0001-ackermann_steering_controller-pass-steering-and-tra.patch"
