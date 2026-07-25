// Copyright 2021 ros2_control Development Team
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef ACKERMANN_HARDWARE__ACKERMANN_SYSTEM_HPP_
#define ACKERMANN_HARDWARE__ACKERMANN_SYSTEM_HPP_

#include <memory>
#include <string>
#include <vector>

#include "hardware_interface/handle.hpp"
#include "hardware_interface/hardware_info.hpp"
#include "hardware_interface/system_interface.hpp"
#include "hardware_interface/types/hardware_interface_return_values.hpp"
#include "rclcpp/clock.hpp"
#include "rclcpp/duration.hpp"
#include "rclcpp/macros.hpp"
#include "rclcpp/time.hpp"
#include "rclcpp_lifecycle/node_interfaces/lifecycle_node_interface.hpp"
#include "rclcpp_lifecycle/state.hpp"

#include "ackermann_hardware/visibility_control.h"
#include "ackermann_hardware/can_comms.hpp"
#include "ackermann_hardware/drive_wheel.hpp"
#include "ackermann_hardware/steering_wheel.hpp"

namespace ackermann_hardware
{
class AckermannHardwareSystem : public hardware_interface::SystemInterface
{

struct Config
{
  std::string left_wheel_name = "";
  std::string right_wheel_name = "";
  std::string left_steering_name = "";
  std::string right_steering_name = "";
  std::string can_interface = "";
  int enc_counts_per_rev = 0;
  std::string imu_name = "";
};

public:
  RCLCPP_SHARED_PTR_DEFINITIONS(AckermannHardwareSystem);

  ACKERMANN_HARDWARE_PUBLIC
  hardware_interface::CallbackReturn on_init(
    const hardware_interface::HardwareInfo & info) override;

  ACKERMANN_HARDWARE_PUBLIC
  std::vector<hardware_interface::StateInterface> export_state_interfaces() override;

  ACKERMANN_HARDWARE_PUBLIC
  std::vector<hardware_interface::CommandInterface> export_command_interfaces() override;

  ACKERMANN_HARDWARE_PUBLIC
  hardware_interface::CallbackReturn on_configure(
    const rclcpp_lifecycle::State & previous_state) override;

  ACKERMANN_HARDWARE_PUBLIC
  hardware_interface::CallbackReturn on_cleanup(
    const rclcpp_lifecycle::State & previous_state) override;

  ACKERMANN_HARDWARE_PUBLIC
  hardware_interface::CallbackReturn on_activate(
    const rclcpp_lifecycle::State & previous_state) override;

  ACKERMANN_HARDWARE_PUBLIC
  hardware_interface::CallbackReturn on_deactivate(
    const rclcpp_lifecycle::State & previous_state) override;

  ACKERMANN_HARDWARE_PUBLIC
  hardware_interface::return_type read(
    const rclcpp::Time & time, const rclcpp::Duration & period) override;

  ACKERMANN_HARDWARE_PUBLIC
  hardware_interface::return_type write(
    const rclcpp::Time & time, const rclcpp::Duration & period) override;

private:

  CanComms can_comms_;
  Config cfg_;
  DriveWheel wheel_l_;
  DriveWheel wheel_r_;
  SteeringWheel steering_;
  
  double imu_accel_[3] = {0.0, 0.0, 0.0};
  double imu_gyro_[3] = {0.0, 0.0, 0.0};
  double imu_orientation_[4] = {0.0, 0.0, 0.0, 1.0}; // Fake

  // IMU Calibration
  bool is_imu_calibrating_ = true;
  int imu_calibration_sample_count_ = 0;
  double imu_gyro_z_sum_ = 0.0;
  double imu_gyro_z_offset_ = 0.0;
  const int IMU_CALIBRATION_SAMPLES = 200; // ~4 seconds at 50Hz

  double dummy_front_wheel_pos_ = 0.0;
  double dummy_front_wheel_vel_ = 0.0;
};

}  // namespace ackermann_hardware

#endif  // ACKERMANN_HARDWARE__ACKERMANN_SYSTEM_HPP_