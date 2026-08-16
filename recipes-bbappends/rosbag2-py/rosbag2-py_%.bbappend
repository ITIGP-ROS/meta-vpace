# rosbag2_py's six pybind11 extensions were installed under the BUILD HOST's
# EXT_SUFFIX -- _reader.cpython-312-x86_64-linux-gnu.so on an aarch64 image. The
# ELF inside each is correct AArch64; only the filename triplet is wrong, and
# CPython looks up the extension by the TARGET suffix, so it never finds them:
#
#   ModuleNotFoundError: No module named 'rosbag2_py._reader'
#
# which takes out record, play, info, convert and reindex alike.
#
# ros_ament_cmake.bbclass inherits python3native only, so the interpreter that
# rosbag2_py's find_package(Python3 COMPONENTS Interpreter Development) runs is
# the native one and reports the host SOABI; the class's -DPYTHON_SOABI is not
# what pybind11_add_module() reads. python3targetconfig points sysconfig at the
# target instead. This is the same fix meta-ros already carries for rclpy, in
# meta-ros2-humble/recipes-bbappends/rclpy/rclpy_3.3.21-1.bbappend -- rclpy is
# the reason 109 other extensions on the image are named correctly and these 6
# are not.
inherit python3targetconfig
