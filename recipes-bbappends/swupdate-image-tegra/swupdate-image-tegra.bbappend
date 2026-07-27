ROOTFS_FILENAME = "${SWUPDATE_CORE_IMAGE_NAME}-humble-${MACHINE}.rootfs.tar.gz"

# Add sha256 hashes to entries that lack them (required by CONFIG_SIGNED_IMAGES)
python do_swuimage:prepend() {
    import os, re
    swdesc = os.path.join(d.getVar('WORKDIR'), 'sw-description')
    entries = ['@@DEPLOY_KERNEL_IMAGE@@', '@@DTBFILE@@', 'tegra-bl.cap', '@@ESP_ARCHIVE@@']
    with open(swdesc, 'r') as f:
        content = f.read()

    for entry in entries:
        pattern = r'(filename = "' + re.escape(entry) + r'";)'
        replacement = r'\1\n\t\t\t\t\t\tsha256 = "$swupdate_get_sha256(' + entry + r')";'
        content = re.sub(pattern, replacement, content)

    with open(swdesc, 'w') as f:
        f.write(content)
}
