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

# State that must not live on the rootfs, since SWUpdate replaces the A/B pair
# wholesale on every flash: secoc/ (SecOC freshness counters, must survive a flash
# or CAN peers reject our frames), network/ (NM's learned WiFi profiles), ota/ (the
# installed-version record; losing it defeats the downgrade gate).
#
# Everything below is guarded on the mount having actually succeeded -- creating
# these under an unmounted /data writes them to the rootfs dir hiding beneath the
# mount point, which looks fine until the next flash silently loses it all.
if mountpoint -q "$DATA_MNT"; then
    # --- SecOC freshness ---------------------------------------------------
    mkdir -p "$DATA_MNT/secoc/update_coordinator"
    chmod 0755 "$DATA_MNT/secoc"
    chmod 0700 "$DATA_MNT/secoc/update_coordinator"

    # Owned by weston (the IVI app's user), not root -- unlike the dir above.
    # Pre-created empty: load_last_fv()'s fscanf fails on empty and falls back
    # to Unix time, matching a never-provisioned box's normal behavior.
    if [ ! -e "$DATA_MNT/secoc/wifi_cred_txfv" ]; then
        : > "$DATA_MNT/secoc/wifi_cred_txfv"
    fi
    # Don't swallow a chown failure: a root-owned file here lets the freshness
    # counter silently stop advancing while wifi_cred_send still reports success.
    if ! chown weston:weston "$DATA_MNT/secoc/wifi_cred_txfv"; then
        echo "mount-data-partition: WARNING: chown weston failed on" \
             "$DATA_MNT/secoc/wifi_cred_txfv - the IVI app cannot persist" \
             "SecOC freshness and its counter will not advance" >&2
    fi
    chmod 0664 "$DATA_MNT/secoc/wifi_cred_txfv"

    # --- NetworkManager ----------------------------------------------------
    # 0700: NetworkManager silently ignores keyfiles readable/writable by
    # anyone but root, since they hold WiFi PSKs in the clear. nm-state is
    # bind-mounted over /var/lib/NetworkManager by a service drop-in and holds
    # the autoconnect preference order, separate from the saved credentials.
    mkdir -p "$DATA_MNT/network/system-connections"
    mkdir -p "$DATA_MNT/network/nm-state"
    chmod 0700 "$DATA_MNT/network" \
               "$DATA_MNT/network/system-connections" \
               "$DATA_MNT/network/nm-state"

    # --- OTA installed version ---------------------------------------------
    # Directory only, not the file itself -- unlike wifi_cred_txfv above, an
    # empty version file would just be rejected; ivi_ota_agent.sh writes it.
    mkdir -p "$DATA_MNT/ota"
    chmod 0700 "$DATA_MNT/ota"

    # --- Nav2 maps ---------------------------------------------------------
    # amcl.launch.py defaults to /data/maps/home.yaml, so seed it from the
    # packaged copy on a fresh /data. cp -n (never overwrite) is the point --
    # a map already in /data may be a newer field survey worth keeping.
    if [ -d /opt/ros/humble/share/ackermann_bringup/maps ]; then
        mkdir -p "$DATA_MNT/maps"
        chmod 0755 "$DATA_MNT/maps"
        cp -n /opt/ros/humble/share/ackermann_bringup/maps/*.yaml \
              /opt/ros/humble/share/ackermann_bringup/maps/*.pgm \
              "$DATA_MNT/maps/" 2>/dev/null || true
    fi
fi
