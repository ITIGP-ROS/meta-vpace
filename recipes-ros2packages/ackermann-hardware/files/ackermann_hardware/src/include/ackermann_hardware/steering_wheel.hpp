#ifndef ACKERMANN_SERIAL_STEERING_WHEEL_HPP
#define ACKERMANN_SERIAL_STEERING_WHEEL_HPP

#include <string>
#include <cmath>
#include <algorithm>
#include <vector>
#include <array>

class SteeringWheel
{
public:
  std::string left_joint_name = "";
  std::string right_joint_name = "";
  
  double left_pos = 0.0;
  double right_pos = 0.0;
  double left_cmd = 0.0;
  double right_cmd = 0.0;

  SteeringWheel() = default;

  void setup(const std::string &left_name, const std::string &right_name)
  {
    left_joint_name = left_name;
    right_joint_name = right_name;
  }

  double get_average_steering_cmd()
  {
      return (left_cmd + right_cmd) / 2.0;
  }

  bool is_right_center_offset_active_ = false;
  double last_angle_rad_ = 0.0;

  int get_servo_duty_cycle(double angle_rad)
  {
      // Due to friction, steering center differs based on direction of arrival
      const int SERVO_MIN = 250;
      const int SERVO_MAX = 512;
      const int SERVO_CENTER_L2C = 356;
      const int SERVO_CENTER_R2C = 331;
      
      struct CalibrationPoint {
          double angle;
          int pwm;
      };

      // Due to lack of steering kinematic calculations in the kit,
      // each angle was calculated by commanding a certain PWM,
      // then commanding a linear velocity of 0.25m/s, forcing the robot 
      // to drive in a circle, and measuring the radius with tape.
      // radius was compared to Ackermann Steering Controller kinematics
      // to derive the angle. This method proved to be good enough.
      // Tabulated results show the left steering is almost identical to
      // simulation, while the right steering is slightly off slower
      // between a turning radius of 0.6m and 0.8m. For future improvement, 
      // either add a servo encoder, or add more data points for right steering.
      static constexpr std::array<CalibrationPoint, 15> lut = {{
          {-0.373302, 512}, // Max Right (60cm radius)
          {-0.342064, 500}, // (66cm radius)
          {-0.309456, 480}, // (73.5cm radius)
          {-0.303644, 460}, // (75cm radius)
          {-0.285713, 440}, // (80cm radius)
          {-0.197396, 420}, // (117.5cm radius)
          {-0.150467, 400}, // (155cm radius)
          {-0.002, 356}, // Center From Right (0 deg)
          { 0.002, 310}, // Center From Left (0 deg)
          { 0.147652, 300}, // (158cm radius)
          { 0.172347, 290}, // (135cm radius)
          { 0.210472, 280}, // (110cm radius)
          { 0.252720, 270}, // (91cm radius)
          { 0.303644, 260}, // (75cm radius)
          { 0.373302, 250}  // Max Left (60cm radius)
      }};

      int target_counts = 343;
      
      if (angle_rad > 0.001 || angle_rad < -0.001) {
          if (angle_rad <= lut.front().angle) {
              target_counts = lut.front().pwm;
          } else if (angle_rad >= lut.back().angle) {
              target_counts = lut.back().pwm;
          } else {
              auto it = std::lower_bound(lut.begin(), lut.end(), angle_rad, 
                  [](const CalibrationPoint& p, double val) { return p.angle < val; });
              
              if (it != lut.end() && it != lut.begin()) {
                  auto prev = it - 1;
                  double t = (angle_rad - prev->angle) / (it->angle - prev->angle);
                  target_counts = prev->pwm + static_cast<int>(t * (it->pwm - prev->pwm));
              }
          }
          is_right_center_offset_active_ = false;
      } else {
          if (last_angle_rad_ < -0.05) {
              is_right_center_offset_active_ = true;
          } else if (last_angle_rad_ > 0.05) {
              is_right_center_offset_active_ = false;
          }
          
          if (is_right_center_offset_active_) {
              target_counts = SERVO_CENTER_R2C;
          } else {
              target_counts = SERVO_CENTER_L2C;
          }
      }
      
      last_angle_rad_ = angle_rad;

      int send_counts = target_counts;

      // SAFETY CLAMP
      if (send_counts < SERVO_MIN) send_counts = SERVO_MIN;
      if (send_counts > SERVO_MAX) send_counts = SERVO_MAX;
      
      return send_counts;
  }

  void update(double dt)
  {
      left_cmd = std::clamp(left_cmd, -0.373302, 0.373302);
      right_cmd = std::clamp(right_cmd, -0.373302, 0.373302);

      // Simulate servo movement speed.
      // Speed = 0.785 rad / 0.5 s = 1.57 rad/s.
      const double MAX_VELOCITY = 1.57; 

      double left_diff = left_cmd - left_pos;
      double left_step = MAX_VELOCITY * dt;
      
      if (std::abs(left_diff) < left_step)
      {
          left_pos = left_cmd;
      }
      else
      {
          left_pos += (left_diff > 0 ? left_step : -left_step);
      }

      double right_diff = right_cmd - right_pos;
      double right_step = MAX_VELOCITY * dt;
      
      if (std::abs(right_diff) < right_step)
      {
          right_pos = right_cmd;
      }
      else
      {
          right_pos += (right_diff > 0 ? right_step : -right_step);
      }
  }
};

#endif // ACKERMANN_SERIAL_STEERING_WHEEL_HPP