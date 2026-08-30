FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

# 0001-screen-share-mirror-full-resolution.patch is deliberately not applied: it's
# a no-op at scale=1, which is what the shipped config uses (client-side scaling
# via QT_SCALE_FACTOR instead -- see weston-rdp.ini). Kept in files/ for reference;
# only re-apply if the compositor ever goes back to [output] scale>1.

# The mirror's encode chain runs on every repaint with no rate limit; throttled to
# ~24fps here. Independent of the patch above and worth keeping on its own merits.
SRC_URI:append = " file://0002-screen-share-throttle-mirror-framerate.patch"
