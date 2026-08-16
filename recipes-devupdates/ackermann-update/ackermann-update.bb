SUMMARY = "SWUpdate payload for Ackermann stack updates (description + bringup + hardware)"
DESCRIPTION = "Bundles ackermann-description, ackermann-bringup and ackermann-hardware \
into a signed .swu. Delivered through the IVI OTA agent: the campaign on the ivi-ota \
feed carries the IVI1 tag, and the agent's single installed-version counter is shared \
with IVI app updates, so ACKERMANN_VERSION must stay above what the board reports."
LICENSE = "CLOSED"

inherit swupdate

SRC_URI = " \
    file://sw-description \
    file://install-ackermann.lua \
"

S = "${WORKDIR}/${PN}"

# Same single counter as IVI_APP_VERSION -- the DESCRIPTION above already says the
# agent's installed-version record is shared, and this is what enforces it rather
# than leaving the two numbers to be kept equal by hand. Bump it in the include.
require conf/include/vpace-ota-version.inc
ACKERMANN_VERSION = "${VPACE_OTA_VERSION}"

# Depend on the ackermann packages to produce the tarballs
IMAGE_DEPENDS = "ackermann-description ackermann-bringup ackermann-hardware"

# Tell SWUpdate to look for the tarballs in DEPLOY_DIR_IMAGE
SWUPDATE_IMAGES = " \
    ackermann-description-${MACHINE}.tar.gz \
    ackermann-bringup-${MACHINE}.tar.gz \
    ackermann-hardware-${MACHINE}.tar.gz \
"

do_swuimage[vardeps] ?= "${@swupdate_find_bitbake_variables(d)}"
do_swuimage[vardeps] += "ACKERMANN_VERSION"
