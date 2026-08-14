SUMMARY = "Mirror the IVI head unit over RDP, without giving up GPU rendering"
DESCRIPTION = "Ships a second Weston configuration that keeps the compositor on the \
DRM backend -- so the IVI app keeps rendering Qt Quick3D on the Jetson GPU -- and \
mirrors that output to a nested RDP compositor. Adds a per-device TLS credential \
generator, a DRM connector forcer so there is an output with no panel attached, and \
an on/off switch. Inert until `weston-remote-display on` is run: the production path \
is the default and is not modified. \
\
Serving the RDP backend DIRECTLY was tried first and does not work on this hardware: \
the compositor comes up on the GPU but NVIDIA's surfaceless EGL display cannot hand \
buffers to clients, so every GL client dies with EGL_BAD_ALLOC. See the note at the \
top of weston-rdp.ini."
LICENSE = "CLOSED"

PV = "1.0"

SRC_URI = "\
    file://weston-rdp.ini \
    file://weston-rdp-tls.service \
    file://weston-rdp-gen-cert \
    file://weston-headless-output.service \
    file://weston-force-output \
    file://weston-remote-display \
    file://weston-remote-display.conf \
"

# Portable across Yocto releases: scarthgap+ defines UNPACKDIR (files land in
# ${WORKDIR}/sources-unpack); older releases unpack straight into ${WORKDIR}.
# The ?= keeps the newer definition and supplies the old default otherwise.
UNPACKDIR ?= "${WORKDIR}"
S = "${UNPACKDIR}"

inherit systemd

SYSTEMD_SERVICE:${PN} = "weston-rdp-tls.service weston-headless-output.service"
# Neither is auto-enabled, and they do not need to be: the vendor drop-in's Wants=
# starts them as dependencies of weston.service, and Wants= works whether or not a
# unit is enabled. Enabling them here as well would just start them twice at boot.
# Masking the drop-in with `weston-remote-display off` correctly takes them out of
# the picture too, which would not happen if they were enabled independently.
SYSTEMD_AUTO_ENABLE = "disable"

# weston   -> the compositor, plus screen-share.so and fullscreen-shell.so
#             (${libdir}/weston/*.so, in the weston package) and rdp-backend.so
#             (${libdir}/libweston-13/, in libweston-13, which weston depends on).
#             Two PACKAGECONFIGs have to be on for those to exist: 'rdp', pinned in
#             conf/distro/vpace.conf, and 'screenshare', which is already in poky's
#             default PACKAGECONFIG for weston. Neither failure is loud -- weston
#             starts fine and the mirror simply never appears -- so
#             `weston-remote-display on` checks for the listener and says so.
# openssl  -> weston-rdp-gen-cert calls the openssl CLI. Poky's openssl recipe has no
#             -bin split (PACKAGES =+ "libcrypto libssl openssl-conf ..."), so
#             ${bindir}/openssl lands in the main package -- the same dependency
#             ivi-ota-agent already relies on for its pkeyutl/base64 work.
RDEPENDS:${PN} = "weston openssl"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${S}/weston-remote-display ${D}${bindir}/weston-remote-display
    install -m 0755 ${S}/weston-rdp-gen-cert   ${D}${bindir}/weston-rdp-gen-cert
    install -m 0755 ${S}/weston-force-output   ${D}${bindir}/weston-force-output

    # Alongside weston-init's weston.ini, never on top of it: that package owns
    # weston.ini and this one owns weston-rdp.ini, so the two never collide.
    install -d ${D}${sysconfdir}/xdg/weston
    install -m 0644 ${S}/weston-rdp.ini ${D}${sysconfdir}/xdg/weston/weston-rdp.ini

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${S}/weston-rdp-tls.service \
        ${D}${systemd_system_unitdir}/weston-rdp-tls.service
    install -m 0644 ${S}/weston-headless-output.service \
        ${D}${systemd_system_unitdir}/weston-headless-output.service

    # The vendor drop-in. THIS is what makes remote display on by default -- it
    # re-points weston.service at weston-rdp.ini. `weston-remote-display off` masks it
    # with a same-named file under ${sysconfdir}/systemd/system rather than editing or
    # deleting this one, so the package's own files are never touched at runtime.
    install -d ${D}${systemd_system_unitdir}/weston.service.d
    install -m 0644 ${S}/weston-remote-display.conf \
        ${D}${systemd_system_unitdir}/weston.service.d/10-remote-display.conf
}

FILES:${PN} += "\
    ${sysconfdir}/xdg/weston/weston-rdp.ini \
    ${systemd_system_unitdir}/weston-rdp-tls.service \
    ${systemd_system_unitdir}/weston-headless-output.service \
    ${systemd_system_unitdir}/weston.service.d/10-remote-display.conf \
"

# Same treatment weston-init gives weston.ini: a local edit to the RDP config (a
# different resolution while bringing a new panel up, say) survives a package upgrade.
CONFFILES:${PN} += "${sysconfdir}/xdg/weston/weston-rdp.ini"

# /etc/weston-rdp is created at RUNTIME by weston-rdp-gen-cert, not here. It has to be
# group-owned by weston so the compositor can read the key, and a chown at do_install
# time would bake the build host's uid/gid into the package -- the same trap ivi.bb
# documents for /var/lib/ivi/media.
