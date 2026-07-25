FILESEXTRAPATHS:prepend := "${THISDIR}/${PN}:"
SRC_URI:append = " \
                    file://mt7601u.cfg \
                    file://iwlwifi-8265.cfg \
"
KERNEL_MODULE_AUTOLOAD:append = " can mttcan"
