SUMMARY = "Vosk speech recognition library (prebuilt)"
LICENSE = "CLOSED"

SRC_URI = " \
    file://vosk_api.h \
    file://libvosk.so \
"

S = "${WORKDIR}"

do_configure[noexec] = "1"
do_compile[noexec] = "1"

do_install() {
    install -d ${D}${includedir}
    install -m 0644 ${WORKDIR}/vosk_api.h ${D}${includedir}/

    install -d ${D}${libdir}
    install -m 0755 ${WORKDIR}/libvosk.so ${D}${libdir}/
}

FILES:${PN} = "${libdir}/libvosk.so"
FILES:${PN}-dev = "${includedir}/vosk_api.h"

# needed as idont have the source code to build the library :(
INSANE_SKIP:${PN} += "already-stripped ldflags buildpaths dev-so"