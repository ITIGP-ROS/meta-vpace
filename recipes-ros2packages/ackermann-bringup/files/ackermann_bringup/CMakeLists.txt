cmake_minimum_required(VERSION 3.8)
project(ackermann_bringup)

if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID MATCHES "Clang")
  add_compile_options(-Wall -Wextra -Wpedantic)
endif()


find_package(ament_cmake REQUIRED)
find_package(rclcpp REQUIRED)
find_package(geometry_msgs REQUIRED)
find_package(std_msgs REQUIRED)
find_package(std_srvs REQUIRED)
find_package(action_msgs REQUIRED)


add_executable(emergency_stop_server src/emergency_stop_server.cpp)
target_include_directories(emergency_stop_server PRIVATE src)
target_link_libraries(emergency_stop_server
  rclcpp::rclcpp
)
ament_target_dependencies(emergency_stop_server
  rclcpp
  std_msgs
  std_srvs
  action_msgs
)
install(TARGETS emergency_stop_server
  DESTINATION lib/${PROJECT_NAME}
)

install(DIRECTORY config launch maps
  DESTINATION share/${PROJECT_NAME}
)


install(PROGRAMS
  src/odom_test.py
  src/circle_test.py
  src/twist_stamper.py
  src/camera_compressor.py
  DESTINATION lib/${PROJECT_NAME}
)

install(CODE "
  execute_process(COMMAND chmod +x
    ${CMAKE_INSTALL_PREFIX}/lib/${PROJECT_NAME}/twist_stamper.py
    ${CMAKE_INSTALL_PREFIX}/lib/${PROJECT_NAME}/odom_test.py
    ${CMAKE_INSTALL_PREFIX}/lib/${PROJECT_NAME}/circle_test.py
    ${CMAKE_INSTALL_PREFIX}/lib/${PROJECT_NAME}/camera_compressor.py
  )
")

ament_package()