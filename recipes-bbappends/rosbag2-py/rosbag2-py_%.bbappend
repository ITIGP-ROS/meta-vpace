# rosbag2_py's pybind11 extensions were built with the build host's EXT_SUFFIX
# instead of the target's (correct ELF, wrong filename triplet), so CPython can't
# find them: "ModuleNotFoundError: No module named 'rosbag2_py._reader'". Caused
# by ros_ament_cmake.bbclass only inheriting python3native; python3targetconfig
# points sysconfig at the target instead. Same fix meta-ros carries for rclpy.
inherit python3targetconfig
