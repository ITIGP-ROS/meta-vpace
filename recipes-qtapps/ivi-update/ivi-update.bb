SUMMARY = "SWUpdate payload for IVI application updates"
LICENSE = "CLOSED"

inherit swupdate

SRC_URI = " \
    file://sw-description \
    file://install-ivi.lua \
"

S = "${WORKDIR}/${PN}"


IVI_APP_VERSION = "1.0.5"

# Depend on ivi to produce the tarball
IMAGE_DEPENDS = "ivi"

# Tell SWUpdate to look for the ivi-app tarball in DEPLOY_DIR_IMAGE
SWUPDATE_IMAGES = "ivi-app-${MACHINE}.tar.gz"

do_swuimage[vardeps] ?= "${@swupdate_find_bitbake_variables(d)}"
do_swuimage[vardeps] += "IVI_APP_VERSION"
