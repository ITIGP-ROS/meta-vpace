SUMMARY = "Mirror the IVI head unit over RDP, without giving up GPU rendering"
DESCRIPTION = "Ships a second Weston config that mirrors the DRM-backed compositor to \
a nested RDP compositor, keeping Qt Quick3D on the GPU. Adds a TLS cert generator, a \
DRM connector forcer for headless boards, and an on/off switch. Serving RDP directly \
was tried first and fails on this hardware (EGL_BAD_ALLOC) -- see weston-rdp.ini."
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
    file://weston-rdp-reset \
    file://weston-rdp-reset.service \
    file://50-weston-rdp-reset \
"

# Portable across Yocto releases: scarthgap+ defines UNPACKDIR (files land in
# ${WORKDIR}/sources-unpack); older releases unpack straight into ${WORKDIR}.
# The ?= keeps the newer definition and supplies the old default otherwise.
UNPACKDIR ?= "${WORKDIR}"
S = "${UNPACKDIR}"

inherit systemd

SYSTEMD_SERVICE:${PN} = "weston-rdp-tls.service weston-headless-output.service \
                         weston-rdp-reset.service"
# Not auto-enabled: the vendor drop-in's Wants= starts them as weston.service
# dependencies regardless, and masking that drop-in via `off` correctly takes them
# out too. weston-rdp-reset.service is separately a oneshot triggered on demand by
# the NM dispatcher hook, not something to run at every boot.
SYSTEMD_AUTO_ENABLE = "disable"

# weston needs PACKAGECONFIG 'rdp' and 'screenshare' on for rdp-backend.so and
# screen-share.so to exist -- neither failure is loud, so `on` checks the listener.
# openssl: weston-rdp-gen-cert calls the CLI, which is in the main package (no
# -bin split in poky's recipe).
RDEPENDS:${PN} = "weston openssl"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${S}/weston-remote-display ${D}${bindir}/weston-remote-display
    install -m 0755 ${S}/weston-rdp-gen-cert   ${D}${bindir}/weston-rdp-gen-cert
    install -m 0755 ${S}/weston-force-output   ${D}${bindir}/weston-force-output
    install -m 0755 ${S}/weston-rdp-reset      ${D}${bindir}/weston-rdp-reset

    # Alongside weston-init's weston.ini, never on top of it: that package owns
    # weston.ini and this one owns weston-rdp.ini, so the two never collide.
    install -d ${D}${sysconfdir}/xdg/weston
    install -m 0644 ${S}/weston-rdp.ini ${D}${sysconfdir}/xdg/weston/weston-rdp.ini

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${S}/weston-rdp-tls.service \
        ${D}${systemd_system_unitdir}/weston-rdp-tls.service
    install -m 0644 ${S}/weston-headless-output.service \
        ${D}${systemd_system_unitdir}/weston-headless-output.service
    install -m 0644 ${S}/weston-rdp-reset.service \
        ${D}${systemd_system_unitdir}/weston-rdp-reset.service

    # Read-only dispatcher dir, not /etc -- same call nm-config.bb makes for
    # static-eth.nmconnection. 0755 root:root is required: NM ignores dispatcher
    # scripts that aren't executable or are group/other-writable. No RDEPENDS on
    # networkmanager -- the hook is just inert without it.
    install -d ${D}${nonarch_libdir}/NetworkManager/dispatcher.d
    install -m 0755 ${S}/50-weston-rdp-reset \
        ${D}${nonarch_libdir}/NetworkManager/dispatcher.d/50-weston-rdp-reset

    # The vendor drop-in that makes remote display on by default. `off` masks it
    # with a same-named /etc file rather than editing this one.
    install -d ${D}${systemd_system_unitdir}/weston.service.d
    install -m 0644 ${S}/weston-remote-display.conf \
        ${D}${systemd_system_unitdir}/weston.service.d/10-remote-display.conf
}

FILES:${PN} += "\
    ${sysconfdir}/xdg/weston/weston-rdp.ini \
    ${systemd_system_unitdir}/weston-rdp-tls.service \
    ${systemd_system_unitdir}/weston-headless-output.service \
    ${systemd_system_unitdir}/weston.service.d/10-remote-display.conf \
    ${systemd_system_unitdir}/weston-rdp-reset.service \
    ${nonarch_libdir}/NetworkManager/dispatcher.d/50-weston-rdp-reset \
"

# Same treatment weston-init gives weston.ini: a local edit to the RDP config (a
# different resolution while bringing a new panel up, say) survives a package upgrade.
CONFFILES:${PN} += "${sysconfdir}/xdg/weston/weston-rdp.ini"

# /etc/weston-rdp is created at RUNTIME by weston-rdp-gen-cert, not here. It has to be
# group-owned by weston so the compositor can read the key, and a chown at do_install
# time would bake the build host's uid/gid into the package -- the same trap ivi.bb
# documents for /var/lib/ivi/media.
