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

#include "ackermann_hardware/ackermann_system.hpp"

#include <chrono>
#include <cmath>
#include <limits>
#include <memory>
#include <vector>

#include "hardware_interface/types/hardware_interface_type_values.hpp"
#include "rclcpp/rclcpp.hpp"

namespace ackermann_hardware
{
hardware_interface::CallbackReturn AckermannHardwareSystem::on_init(
  const hardware_interface::HardwareInfo & info)
{
  if (
    hardware_interface::SystemInterface::on_init(info) !=
    hardware_interface::CallbackReturn::SUCCESS)
  {
    return hardware_interface::CallbackReturn::ERROR;
  }

  cfg_.left_wheel_name = info_.hardware_parameters["left_wheel_name"];
  cfg_.right_wheel_name = info_.hardware_parameters["right_wheel_name"];
  cfg_.left_steering_name = info_.hardware_parameters["left_steering_name"];
  cfg_.right_steering_name = info_.hardware_parameters["right_steering_name"];
  cfg_.can_interface = info_.hardware_parameters["can_interface"];
  cfg_.enc_counts_per_rev = std::stoi(info_.hardware_parameters["enc_counts_per_rev"]);
  cfg_.imu_name = info_.hardware_parameters["imu_name"];

  wheel_l_.setup(cfg_.left_wheel_name, cfg_.enc_counts_per_rev);
  wheel_r_.setup(cfg_.right_wheel_name, cfg_.enc_counts_per_rev);
  steering_.setup(cfg_.left_steering_name, cfg_.right_steering_name);

  for (const hardware_interface::ComponentInfo & joint : info_.joints)
  {
    // DRIVE WHEELS
    if (joint.name == cfg_.left_wheel_name || joint.name == cfg_.right_wheel_name)
    {
       if (joint.command_interfaces.size() != 1)
       {
         RCLCPP_FATAL(
           rclcpp::get_logger("AckermannHardwareSystem"),
           "Joint '%s' has %zu command interfaces found. 1 expected.", joint.name.c_str(),
           joint.command_interfaces.size());
         return hardware_interface::CallbackReturn::ERROR;
       }
   
       if (joint.command_interfaces[0].name != hardware_interface::HW_IF_VELOCITY)
       {
         RCLCPP_FATAL(
           rclcpp::get_logger("AckermannHardwareSystem"),
           "Joint '%s' have %s command interfaces found. '%s' expected.", joint.name.c_str(),
           joint.command_interfaces[0].name.c_str(), hardware_interface::HW_IF_VELOCITY);
         return hardware_interface::CallbackReturn::ERROR;
       }
   
       if (joint.state_interfaces.size() != 2)
       {
         RCLCPP_FATAL(
           rclcpp::get_logger("AckermannHardwareSystem"),
           "Joint '%s' has %zu state interface. 2 expected.", joint.name.c_str(),
           joint.state_interfaces.size());
         return hardware_interface::CallbackReturn::ERROR;
       }
   
       if (joint.state_interfaces[0].name != hardware_interface::HW_IF_POSITION)
       {
         RCLCPP_FATAL(
           rclcpp::get_logger("AckermannHardwareSystem"),
           "Joint '%s' have '%s' as first state interface. '%s' expected.", joint.name.c_str(),
           joint.state_interfaces[0].name.c_str(), hardware_interface::HW_IF_POSITION);
         return hardware_interface::CallbackReturn::ERROR;
       }
   
       if (joint.state_interfaces[1].name != hardware_interface::HW_IF_VELOCITY)
       {
         RCLCPP_FATAL(
           rclcpp::get_logger("AckermannHardwareSystem"),
           "Joint '%s' have '%s' as second state interface. '%s' expected.", joint.name.c_str(),
           joint.state_interfaces[1].name.c_str(), hardware_interface::HW_IF_VELOCITY);
         return hardware_interface::CallbackReturn::ERROR;
       }
    }
    
    // STEERING JOINTS
    else if (joint.name == cfg_.left_steering_name || joint.name == cfg_.right_steering_name)
    {
       if (joint.command_interfaces.size() != 1)
       {
         RCLCPP_FATAL(
           rclcpp::get_logger("AckermannHardwareSystem"),
           "Joint '%s' has %zu command interfaces found. 1 expected.", joint.name.c_str(),
           joint.command_interfaces.size());
         return hardware_interface::CallbackReturn::ERROR;
       }
   
       if (joint.command_interfaces[0].name != hardware_interface::HW_IF_POSITION)
       {
         RCLCPP_FATAL(
           rclcpp::get_logger("AckermannHardwareSystem"),
           "Joint '%s' have %s command interfaces found. '%s' expected.", joint.name.c_str(),
           joint.command_interfaces[0].name.c_str(), hardware_interface::HW_IF_POSITION);
         return hardware_interface::CallbackReturn::ERROR;
       }
   
       if (joint.state_interfaces.size() != 1)
       {
         RCLCPP_FATAL(
           rclcpp::get_logger("AckermannHardwareSystem"),
           "Joint '%s' has %zu state interface. 1 expected.", joint.name.c_str(),
           joint.state_interfaces.size());
         return hardware_interface::CallbackReturn::ERROR;
       }
   
       if (joint.state_interfaces[0].name != hardware_interface::HW_IF_POSITION)
       {
         RCLCPP_FATAL(
           rclcpp::get_logger("AckermannHardwareSystem"),
           "Joint '%s' have '%s' as first state interface. '%s' expected.", joint.name.c_str(),
           joint.state_interfaces[0].name.c_str(), hardware_interface::HW_IF_POSITION);
         return hardware_interface::CallbackReturn::ERROR;
       }
    }
    // IMU SENSOR
    else if (joint.name == cfg_.imu_name)
    {
       if (joint.state_interfaces.size() != 10) // ax, ay, az, gx, gy, gz, ox, oy, oz, ow
       {
         RCLCPP_WARN(
           rclcpp::get_logger("AckermannHardwareSystem"),
           "Sensor '%s' state interface count not validated strictly.", joint.name.c_str());
       }
    }
    else
    {
        RCLCPP_WARN(rclcpp::get_logger("AckermannHardwareSystem"), "Joint '%s' is not listed in configuration", joint.name.c_str());
    }
  }

  return hardware_interface::CallbackReturn::SUCCESS;
}

std::vector<hardware_interface::StateInterface> AckermannHardwareSystem::export_state_interfaces()
{
  std::vector<hardware_interface::StateInterface> state_interfaces;

  // Drive Wheels
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    wheel_l_.name, hardware_interface::HW_IF_POSITION, &wheel_l_.pos));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    wheel_l_.name, hardware_interface::HW_IF_VELOCITY, &wheel_l_.vel));

  state_interfaces.emplace_back(hardware_interface::StateInterface(
    wheel_r_.name, hardware_interface::HW_IF_POSITION, &wheel_r_.pos));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    wheel_r_.name, hardware_interface::HW_IF_VELOCITY, &wheel_r_.vel));

  // Steering Joints (Servo)
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    steering_.left_joint_name, hardware_interface::HW_IF_POSITION, &steering_.left_pos));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    steering_.right_joint_name, hardware_interface::HW_IF_POSITION, &steering_.right_pos));

  // Dummy Front Wheels for RVIZ
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    "front_left_wheel_joint", hardware_interface::HW_IF_POSITION, &dummy_front_wheel_pos_));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    "front_left_wheel_joint", hardware_interface::HW_IF_VELOCITY, &dummy_front_wheel_vel_));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    "front_right_wheel_joint", hardware_interface::HW_IF_POSITION, &dummy_front_wheel_pos_));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    "front_right_wheel_joint", hardware_interface::HW_IF_VELOCITY, &dummy_front_wheel_vel_));

  // IMU State Interfaces
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    cfg_.imu_name, "linear_acceleration.x", &imu_accel_[0]));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    cfg_.imu_name, "linear_acceleration.y", &imu_accel_[1]));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    cfg_.imu_name, "linear_acceleration.z", &imu_accel_[2]));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    cfg_.imu_name, "angular_velocity.x", &imu_gyro_[0]));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    cfg_.imu_name, "angular_velocity.y", &imu_gyro_[1]));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    cfg_.imu_name, "angular_velocity.z", &imu_gyro_[2]));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    cfg_.imu_name, "orientation.x", &imu_orientation_[0]));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    cfg_.imu_name, "orientation.y", &imu_orientation_[1]));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    cfg_.imu_name, "orientation.z", &imu_orientation_[2]));
  state_interfaces.emplace_back(hardware_interface::StateInterface(
    cfg_.imu_name, "orientation.w", &imu_orientation_[3]));

  return state_interfaces;
}

std::vector<hardware_interface::CommandInterface> AckermannHardwareSystem::export_command_interfaces()
{
  std::vector<hardware_interface::CommandInterface> command_interfaces;

  // Drive Wheels
  command_interfaces.emplace_back(hardware_interface::CommandInterface(
    wheel_l_.name, hardware_interface::HW_IF_VELOCITY, &wheel_l_.cmd));

  command_interfaces.emplace_back(hardware_interface::CommandInterface(
    wheel_r_.name, hardware_interface::HW_IF_VELOCITY, &wheel_r_.cmd));

  // Steering Joints (Servo)
  command_interfaces.emplace_back(hardware_interface::CommandInterface(
    steering_.left_joint_name, hardware_interface::HW_IF_POSITION, &steering_.left_cmd));
  command_interfaces.emplace_back(hardware_interface::CommandInterface(
    steering_.right_joint_name, hardware_interface::HW_IF_POSITION, &steering_.right_cmd));

  return command_interfaces;
}

hardware_interface::CallbackReturn AckermannHardwareSystem::on_configure(
  const rclcpp_lifecycle::State & /*previous_state*/)
{
  RCLCPP_INFO(rclcpp::get_logger("AckermannHardwareSystem"), "Configuring ...please wait...");
  if (can_comms_.connected())
  {
    can_comms_.disconnect();
  }
  if (!can_comms_.connect(cfg_.can_interface))
  {
    RCLCPP_FATAL(rclcpp::get_logger("AckermannHardwareSystem"), "Failed to connect to CAN interface: %s", cfg_.can_interface.c_str());
    return hardware_interface::CallbackReturn::ERROR;
  }

  RCLCPP_INFO(rclcpp::get_logger("AckermannHardwareSystem"), "Successfully configured!");

  return hardware_interface::CallbackReturn::SUCCESS;
}

hardware_interface::CallbackReturn AckermannHardwareSystem::on_cleanup(
  const rclcpp_lifecycle::State & /*previous_state*/)
{
  RCLCPP_INFO(rclcpp::get_logger("AckermannHardwareSystem"), "Cleaning up ...please wait...");
  if (can_comms_.connected())
  {
    can_comms_.disconnect();
  }
  RCLCPP_INFO(rclcpp::get_logger("AckermannHardwareSystem"), "Successfully cleaned up!");

  return hardware_interface::CallbackReturn::SUCCESS;
}

hardware_interface::CallbackReturn AckermannHardwareSystem::on_activate(
  const rclcpp_lifecycle::State & /*previous_state*/)
{
  RCLCPP_INFO(rclcpp::get_logger("AckermannHardwareSystem"), "Activating ...please wait...");

  if (!can_comms_.connected())
  {
    return hardware_interface::CallbackReturn::ERROR;
  }
  
  // Send hardware reset via CAN (0x202)
  can_comms_.send_hardware_reset();

  // Center steering — Tiva-C handles angle->PWM mapping locally
  can_comms_.set_steering(0.0f);
  
  // Delay to avoid movement interference with IMU Calibration
  rclcpp::sleep_for(std::chrono::milliseconds(1500));

  // Reset IMU Calibration state on activate
  is_imu_calibrating_ = true;
  imu_calibration_sample_count_ = 0;
  imu_gyro_z_sum_ = 0.0;
  imu_gyro_z_offset_ = 0.0;

  RCLCPP_INFO(rclcpp::get_logger("AckermannHardwareSystem"), "Successfully activated!");

  return hardware_interface::CallbackReturn::SUCCESS;
}

hardware_interface::CallbackReturn AckermannHardwareSystem::on_deactivate(
  const rclcpp_lifecycle::State & /*previous_state*/)
{
  RCLCPP_INFO(rclcpp::get_logger("AckermannHardwareSystem"), "Deactivating ...please wait...");
  
  if (can_comms_.connected())
  {
    can_comms_.set_steering(0.0f);
  }
  
  RCLCPP_INFO(rclcpp::get_logger("AckermannHardwareSystem"), "Successfully deactivated!");

  return hardware_interface::CallbackReturn::SUCCESS;
}

hardware_interface::return_type AckermannHardwareSystem::read(
  const rclcpp::Time & /*time*/, const rclcpp::Duration & period)
{
  if (!can_comms_.connected())
  {
    return hardware_interface::return_type::ERROR;
  }
  
  int l_enc = 0, r_enc = 0;
  double imu_data[6] = {0.0};
  bool is_imu_reset = false;
  
  if (can_comms_.read_sensor_values(l_enc, r_enc, imu_data, is_imu_reset))
  {
      static bool prev_imu_reset = false;
      if (is_imu_reset && !prev_imu_reset) {
          RCLCPP_WARN(rclcpp::get_logger("AckermannHardwareSystem"), 
                      "Firmware reported IMU crash! Auto-healing, retaining previous IMU calibration...");
      }
      prev_imu_reset = is_imu_reset;
      
    wheel_l_.enc = l_enc;
    wheel_r_.enc = r_enc;
    
    imu_accel_[0] = imu_data[0];
    imu_accel_[1] = imu_data[1];
    imu_accel_[2] = imu_data[2];
    
    imu_gyro_[0] = imu_data[3];
    imu_gyro_[1] = imu_data[4];
    
    // GyroZ is already inverted to robot frame by Tiva-C firmware (DBC spec).
    // No host-side inversion needed.
    double raw_gyro_z = imu_data[5];
    
    if (is_imu_calibrating_) {
        if (!is_imu_reset) {
            imu_gyro_z_sum_ += raw_gyro_z;
            imu_calibration_sample_count_++;
            
            if (imu_calibration_sample_count_ >= IMU_CALIBRATION_SAMPLES) {
                imu_gyro_z_offset_ = imu_gyro_z_sum_ / IMU_CALIBRATION_SAMPLES;
                is_imu_calibrating_ = false;
                RCLCPP_INFO(rclcpp::get_logger("AckermannHardwareSystem"), 
                            "IMU Calibration Complete. Z-axis Bias: %.4f rad/s", imu_gyro_z_offset_);
            }
        }
        
        // Enforce strict 0 to prevent drift in EKF during setup
        imu_gyro_[2] = 0.0; 
    } else if (is_imu_reset) {
        // Enforce strict 0 during hardware crash to prevent drift
        imu_gyro_[2] = 0.0; 
    } else {
        // Apply calibration offset
        imu_gyro_[2] = (raw_gyro_z - imu_gyro_z_offset_); 
    }
  }

  double delta_seconds = period.seconds();

  double pos_prev = wheel_l_.pos;
  wheel_l_.pos = wheel_l_.calc_enc_angle();
  if (delta_seconds > 0.0001) {
    wheel_l_.vel = (wheel_l_.pos - pos_prev) / delta_seconds;
  } else {
    wheel_l_.vel = 0.0;
  }

  pos_prev = wheel_r_.pos;
  wheel_r_.pos = wheel_r_.calc_enc_angle();
  if (delta_seconds > 0.0001) {
    wheel_r_.vel = (wheel_r_.pos - pos_prev) / delta_seconds;
  } else {
    wheel_r_.vel = 0.0;
  }

  // Fake steering position due to lack of servo encoder
  steering_.update(delta_seconds);

  return hardware_interface::return_type::OK;
}

hardware_interface::return_type AckermannHardwareSystem::write(
  const rclcpp::Time & /*time*/, const rclcpp::Duration & /*period*/)
{
  if (!can_comms_.connected())
  {
    return hardware_interface::return_type::ERROR;
  }

  double avg_steer_angle = steering_.get_average_steering_cmd();
  
  // Send physical steering angle in radians directly.
  // Tiva-C handles angle->PWM mapping locally per DBC.
  can_comms_.set_steering(static_cast<float>(avg_steer_angle));
  
  float left_vel = wheel_l_.cmd;
  float right_vel = wheel_r_.cmd;
  
  can_comms_.set_motor_values(left_vel, right_vel);
  return hardware_interface::return_type::OK;
}

}  // namespace ackermann_hardware

#include "pluginlib/class_list_macros.hpp"
PLUGINLIB_EXPORT_CLASS(
  ackermann_hardware::AckermannHardwareSystem, hardware_interface::SystemInterface)