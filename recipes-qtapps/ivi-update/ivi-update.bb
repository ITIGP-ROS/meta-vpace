SUMMARY = "SWUpdate payload for IVI application updates"
LICENSE = "CLOSED"

inherit swupdate

SRC_URI = " \
    file://sw-description \
    file://install-ivi.lua \
"

S = "${WORKDIR}/${PN}"


# Not a literal any more. The agent has ONE installed-version counter shared with
# ackermann-update, and ivi-ota-agent generates its INITIAL_VERSION floor from the
# same variable -- see conf/include/vpace-ota-version.inc for why they cannot
# drift apart. Bump it there, not here.
require conf/include/vpace-ota-version.inc
IVI_APP_VERSION = "${VPACE_OTA_VERSION}"

# Depend on ivi to produce the tarball
IMAGE_DEPENDS = "ivi"

# Tell SWUpdate to look for the ivi-app tarball in DEPLOY_DIR_IMAGE
SWUPDATE_IMAGES = "ivi-app-${MACHINE}.tar.gz"

do_swuimage[vardeps] ?= "${@swupdate_find_bitbake_variables(d)}"
do_swuimage[vardeps] += "IVI_APP_VERSION"
