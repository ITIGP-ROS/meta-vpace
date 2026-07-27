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
