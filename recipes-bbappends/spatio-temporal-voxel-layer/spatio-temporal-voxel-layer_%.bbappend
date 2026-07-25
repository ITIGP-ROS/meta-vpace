# this recipe for some reason triggers "out of memory" error when building with alot of threads so use like 4 threads 

# there must be a better way to do this, but that the solution I found for now

ROS_BUILD_DEPENDS += " \
    rosidl-adapter \
    rosidl-adapter-native \
    ament-cmake-ros \
    ament-cmake-gmock \
    ament-cmake-gtest \
    ament-cmake-pytest \
    ament-cmake-auto \
    python3-numpy-native \
"

DEPENDS += " \
    rosidl-parser \
    python3-lark-parser-native \
    rpyutils-native \
"

do_compile:prepend:class-target() {
    export PYTHONPATH=${STAGING_DIR_HOST}${ros_libdir}/${PYTHON_DIR}/site-packages:$PYTHONPATH
}
