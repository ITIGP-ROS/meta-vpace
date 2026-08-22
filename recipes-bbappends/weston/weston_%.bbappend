FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# 0001-screen-share-mirror-full-resolution.patch IS DELIBERATELY NOT APPLIED.
#
# It is still in files/ for reference, but it is a no-op on the path we actually
# ship, so it is not worth carrying across weston upgrades.
#
# It swapped screen-share.c's use of so->output->width for
# so->output->current_mode->width, to stop the mirror throwing away the extra
# pixels of a HiDPI-scaled output. But libweston/compositor.c:6939 computes
#     output->width = current_mode->width / scale
# (via convert_size_by_transform_scale), so the two expressions are identical
# whenever scale == 1 -- and the shipped config keeps the compositor at scale=1
# on purpose, doing the 2x on the client with QT_SCALE_FACTOR instead. See the
# long comment in weston-remote-display's weston-rdp.ini for why the
# compositor-side [output] scale=2 route was abandoned.
#
# Re-apply this only if that decision is ever revisited and the output goes back
# to scale > 1. Until then it changes nothing.

# The mirror's readback/composite/encode/send chain runs on every compositor
# repaint with no rate limit. Throttled to ~24fps. This one is NOT tied to the
# scale decision above and is worth keeping on its own merits -- it matters more
# now, not less, because the mirror is carrying a 2048x1200 frame (4x the pixels
# of the old 1024x600) on a Jetson Orin Nano whose CPU and GPU share memory
# bandwidth with the rest of the vehicle's workload.
SRC_URI:append = " file://0002-screen-share-throttle-mirror-framerate.patch"
