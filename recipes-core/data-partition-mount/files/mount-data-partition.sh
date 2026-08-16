#!/bin/sh
DATA_DEV="/dev/nvme0n1p15"
DATA_MNT="/data"

if ! grep -q "$DATA_MNT" /etc/fstab; then
    echo "$DATA_DEV $DATA_MNT ext4 defaults 0 2" >> /etc/fstab
fi

if ! mountpoint -q "$DATA_MNT"; then
    mkdir -p "$DATA_MNT"
    blkid "$DATA_DEV" || mkfs.ext4 "$DATA_DEV"
    mount "$DATA_MNT"
fi

# ---------------------------------------------------------------------------
# STATE THAT MUST NOT LIVE ON THE ROOTFS
#
# The rootfs is the A/B pair nvme0n1p1/p2 that SWUpdate replaces wholesale, so
# anything under /etc or /var is gone on every flash. Two kinds of state break
# badly when that happens, and both are rooted here instead:
#
#   secoc/    SecOC freshness counters. The peers on the CAN bus (the QNX
#             cluster and the ESP32 body ECU) keep counting across OUR flash.
#             If ours reset, our frames are rejected -- and the rejection is
#             logged on the OTHER processor, so on this box it presents as
#             "the OTA request just never gets approved", with nothing in our
#             own journal to explain it.
#
#   network/  NetworkManager's learned WiFi profiles and its state directory,
#             so a reflashed board rejoins the last network it was on instead
#             of needing a keyboard and a monitor.
#
#   ota/      The OTA agent's record of which version is installed -- one file,
#             shared by the IVI app and ackermann payload channels. Lose it and
#             the board reports its INITIAL_VERSION floor instead: it reinstalls
#             the campaign it is already running (holding the vehicle while it
#             does), and the ALLOW_DOWNGRADE gate stops rejecting replayed old
#             packages, which is the one attack swupdate's signature check does
#             not cover.
#
# EVERYTHING BELOW IS GUARDED ON THE MOUNT HAVING ACTUALLY SUCCEEDED. Creating
# these under an unmounted /data writes them to the rootfs directory hiding
# beneath the mount point, which looks like it works and then silently loses
# every value on the next flash -- the exact failure this arrangement exists to
# prevent. A guard that is skipped is worse than no guard at all here.
#
# Plain mkdir/chmod rather than `install -d`: this runs before local-fs.target
# on a BusyBox userland, and mkdir is the option that is certain to be there.
# ---------------------------------------------------------------------------
if mountpoint -q "$DATA_MNT"; then
    # --- SecOC freshness ---------------------------------------------------
    mkdir -p "$DATA_MNT/secoc/update_coordinator"
    chmod 0755 "$DATA_MNT/secoc"
    chmod 0700 "$DATA_MNT/secoc/update_coordinator"

    # wifi_cred_send is launched by the IVI head-unit app, which runs as
    # `weston`, not root -- so this one file has to be group/owner writable by
    # it while the directory above stays root-owned.
    #
    # Pre-created EMPTY on purpose: load_last_fv()'s fscanf fails on an empty
    # file and returns 0, and next_freshness() then falls back to Unix time,
    # which is exactly how a never-provisioned box has always behaved.
    if [ ! -e "$DATA_MNT/secoc/wifi_cred_txfv" ]; then
        : > "$DATA_MNT/secoc/wifi_cred_txfv"
    fi
    # Do NOT swallow a chown failure here. A root-owned store leaves weston
    # unable to write it, wifi_cred_send prints one warning to a stderr nobody
    # is reading, and the send still SUCCEEDS -- so the counter quietly stops
    # advancing and nothing looks wrong until a receiver starts rejecting us.
    # This runs under systemd, so the message lands in the journal.
    if ! chown weston:weston "$DATA_MNT/secoc/wifi_cred_txfv"; then
        echo "mount-data-partition: WARNING: chown weston failed on" \
             "$DATA_MNT/secoc/wifi_cred_txfv - the IVI app cannot persist" \
             "SecOC freshness and its counter will not advance" >&2
    fi
    chmod 0664 "$DATA_MNT/secoc/wifi_cred_txfv"

    # --- NetworkManager ----------------------------------------------------
    # 0700 is NOT cosmetic: NetworkManager silently ignores any keyfile that is
    # readable or writable by a user or group other than root, because they
    # hold WiFi PSKs in the clear. A looser mode here means saved networks stop
    # being loaded with no error worth finding.
    #
    # nm-state is bind-mounted over /var/lib/NetworkManager by a drop-in on
    # NetworkManager.service. It carries `timestamps` (which network was last
    # used -- the tie-break that decides WHICH saved profile autoconnects) and
    # `seen-bssids` (how hidden SSIDs are found at all). Persisting the
    # profiles without this directory keeps the passwords but loses the
    # preference order.
    mkdir -p "$DATA_MNT/network/system-connections"
    mkdir -p "$DATA_MNT/network/nm-state"
    chmod 0700 "$DATA_MNT/network" \
               "$DATA_MNT/network/system-connections" \
               "$DATA_MNT/network/nm-state"

    # --- OTA installed version ---------------------------------------------
    # Only the directory -- the OPPOSITE of the wifi_cred_txfv treatment above.
    # There, an empty file is exactly equivalent to a missing one. Here it is
    # not: a version file is either a valid semver or it is absent, and
    # ivi_ota_agent.sh runs as root and writes it itself after a successful
    # install. Pre-creating an empty one would only give installed_version()
    # something to reject.
    mkdir -p "$DATA_MNT/ota"
    chmod 0700 "$DATA_MNT/ota"
fi
