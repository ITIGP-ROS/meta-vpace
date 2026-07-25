# Header-only library: create empty runtime package so RDEPENDS resolves 
# there is an active issue on GitHub about this: https://github.com/ros/meta-ros/issues/1722 
ALLOW_EMPTY:${PN} = "1"