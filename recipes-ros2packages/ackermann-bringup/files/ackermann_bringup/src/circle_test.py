#!/usr/bin/env python3
"""
Description:
    Robot completes one full circle until actual yaw reaches 360°.
    Verifies angular velocity, steering, and odometry accuracy.

Purpose:
    Comparing Simulation and Real Robot Results for steering.

Note:
    Parameters need to be configured for each robot.
"""

import rclpy
from rclpy.node import Node
from rclpy.callback_groups import ReentrantCallbackGroup
from geometry_msgs.msg import TwistStamped
from nav_msgs.msg import Odometry
import math
from dataclasses import dataclass

@dataclass
class OdomData:
    x: float = 0.0
    y: float = 0.0
    theta: float = 0.0
    timestamp: float = 0.0

class CircleTestNode(Node):
    def __init__(self):
        super().__init__('circle_test_node')
        
        self.declare_parameter('circle_radius', 0.75)  # meters
        self.declare_parameter('circle_velocity', 0.25)  # m/s
        self.declare_parameter('direction', 'left')  # 'left' or 'right'
        self.declare_parameter('wheelbase', 0.23529)  # meters
        self.declare_parameter('max_steering_angle', 0.3)  # radians
        self.declare_parameter('tolerance_position', 0.2)  # meters
        self.declare_parameter('tolerance_yaw', 10.0)  # degrees
        
        self.circle_radius = self.get_parameter('circle_radius').value
        self.circle_velocity = self.get_parameter('circle_velocity').value
        self.direction = self.get_parameter('direction').value
        self.wheelbase = self.get_parameter('wheelbase').value
        self.max_steering_angle = self.get_parameter('max_steering_angle').value
        self.tolerance_position = self.get_parameter('tolerance_position').value
        self.tolerance_yaw = math.radians(self.get_parameter('tolerance_yaw').value)


        self.odom_data = OdomData()
        self.start_odom = OdomData()
        self.end_odom = OdomData()
        self.test_started = False
        self.test_finished = False
        self.start_time = 0.0
        self.yaw_accumulated = 0.0
        self.last_yaw = 0.0
        
        cb_group = ReentrantCallbackGroup()
        
        self.reference_pub = self.create_publisher(
            TwistStamped,
            '/cmd_vel_stamped',
            10
        )
        
        self.odom_sub = self.create_subscription(
            Odometry,
            '/odom',
            self.odom_callback,
            10,
            callback_group=cb_group
        )
        
        self.test_timer = self.create_timer(0.05, self.test_loop, callback_group=cb_group)
        
        self.angular_velocity = self.circle_velocity / self.circle_radius
        self.steering_angle = math.atan(self.wheelbase / self.circle_radius)
        
        if self.direction.lower() == 'right':
            self.angular_velocity = -self.angular_velocity
            self.steering_angle = -self.steering_angle
            
        self.steering_angle = max(-self.max_steering_angle, min(self.max_steering_angle, self.steering_angle))
        self.target_rotation = 2 * math.pi
        
        self.get_logger().info("Circle Test Node initialized (ROTATION-BASED)")
        self.get_logger().info(f"  Direction: {self.direction.upper()}")
        self.get_logger().info(f"  Circle radius: {self.circle_radius} m")
        self.get_logger().info(f"  Velocity: {self.circle_velocity} m/s")
        self.get_logger().info(f"  Wheelbase: {self.wheelbase} m")
        self.get_logger().info(f"  Steering angle: {math.degrees(self.steering_angle):.2f}°")
        self.get_logger().info(f"  Angular velocity: {self.angular_velocity:.3f} rad/s")
        self.get_logger().info(f"  Target rotation: {math.degrees(self.target_rotation):.1f}° (360°)")
        self.get_logger().info(f"  Position tolerance: ±{self.tolerance_position} m")
        self.get_logger().info(f"  Yaw tolerance: ±{math.degrees(self.tolerance_yaw):.1f}°")
        self.get_logger().info("Waiting for odometry data...")
    
    def odom_callback(self, msg: Odometry):
        self.odom_data.x = msg.pose.pose.position.x
        self.odom_data.y = msg.pose.pose.position.y
        
        qx = msg.pose.pose.orientation.x
        qy = msg.pose.pose.orientation.y
        qz = msg.pose.pose.orientation.z
        qw = msg.pose.pose.orientation.w
        self.odom_data.theta = math.atan2(2*(qw*qz + qx*qy), 1 - 2*(qy*qy + qz*qz))
        
        self.odom_data.timestamp = msg.header.stamp.sec + msg.header.stamp.nanosec / 1e9
    
    def accumulate_yaw(self):
        yaw_delta = self.odom_data.theta - self.last_yaw
        
        if yaw_delta > math.pi:
            yaw_delta -= 2 * math.pi
        elif yaw_delta < -math.pi:
            yaw_delta += 2 * math.pi
        
        self.yaw_accumulated += yaw_delta
        self.last_yaw = self.odom_data.theta
        
        return self.yaw_accumulated
    
    def test_loop(self):
        now = self.get_clock().now().nanoseconds / 1e9
        
        if not self.test_started:
            if self.odom_data.timestamp > 0:
                self.get_logger().info("Odometry received, starting circle test...")
                self.test_started = True
                self.start_time = now
                self.start_odom = OdomData(
                    x=self.odom_data.x,
                    y=self.odom_data.y,
                    theta=self.odom_data.theta,
                    timestamp=self.odom_data.timestamp
                )
                self.last_yaw = self.odom_data.theta
            return
        
        elapsed = now - self.start_time
        accumulated_rotation = self.accumulate_yaw()
        
        if abs(accumulated_rotation) < self.target_rotation and not self.test_finished:
            clock_now = self.get_clock().now().to_msg()
            command = TwistStamped()
            command.header.stamp = clock_now
            command.header.frame_id = "base_link"
            command.twist.linear.x = self.circle_velocity
            command.twist.angular.z = self.angular_velocity
            
            self.reference_pub.publish(command)
            
            if int(abs(accumulated_rotation) * 10) % 10 == 0:
                self.get_logger().info(
                    f"  [{elapsed:.2f}s] Rotation: {math.degrees(accumulated_rotation):.1f}°, "
                    f"Pos: ({self.odom_data.x:.4f}, {self.odom_data.y:.4f}), "
                    f"Yaw: {math.degrees(self.odom_data.theta):.1f}°"
                )
        
        elif abs(accumulated_rotation) >= self.target_rotation and not self.test_finished:
            stop_cmd = TwistStamped()
            stop_cmd.header.stamp = self.get_clock().now().to_msg()
            stop_cmd.header.frame_id = "base_link"
            stop_cmd.twist.linear.x = 0.0
            stop_cmd.twist.angular.z = 0.0
            
            self.reference_pub.publish(stop_cmd)
            
            self.end_odom = OdomData(
                x=self.odom_data.x,
                y=self.odom_data.y,
                theta=self.odom_data.theta
            )
            
            self.test_finished = True
            self.analyze_results(elapsed)
            
            rclpy.shutdown()
    
    def analyze_results(self, elapsed_time):
        dx = self.end_odom.x - self.start_odom.x
        dy = self.end_odom.y - self.start_odom.y
        position_error = math.sqrt(dx**2 + dy**2)
        
        yaw_error = self.end_odom.theta - self.start_odom.theta
        
        while yaw_error > math.pi:
            yaw_error -= 2 * math.pi
        while yaw_error < -math.pi:
            yaw_error += 2 * math.pi
        
        position_passed = position_error < self.tolerance_position
        yaw_passed = abs(yaw_error) < self.tolerance_yaw
        overall_passed = position_passed and yaw_passed
        
        status = "✓ PASSED" if overall_passed else "✗ FAILED"
        
        self.get_logger().info("\n")
        self.get_logger().info("CIRCULAR PATH TEST RESULTS (ROTATION-BASED)")
        self.get_logger().info("\n")
        
        self.get_logger().info("Circle Parameters:")
        self.get_logger().info(f"  Radius: {self.circle_radius} m")
        self.get_logger().info(f"  Velocity: {self.circle_velocity} m/s")
        self.get_logger().info(f"  Steering angle: {math.degrees(self.steering_angle):.2f}°")
        self.get_logger().info(f"  Target rotation: {math.degrees(self.target_rotation):.1f}°")
        self.get_logger().info(f"  Actual time taken: {elapsed_time:.2f} s")
        self.get_logger().info(f"  Accumulated rotation: {math.degrees(self.yaw_accumulated):.1f}°")
        
        self.get_logger().info("\n")
        self.get_logger().info("Start Position: ({:.6f}, {:.6f})".format(self.start_odom.x, self.start_odom.y))
        self.get_logger().info(f"Start Yaw: {math.degrees(self.start_odom.theta):.2f}°")
        
        self.get_logger().info("\n")
        self.get_logger().info("End Position: ({:.6f}, {:.6f})".format(self.end_odom.x, self.end_odom.y))
        self.get_logger().info(f"End Yaw: {math.degrees(self.end_odom.theta):.2f}°")
        
        self.get_logger().info("\n")
        self.get_logger().info("Position Error: {:.6f} m".format(position_error))
        self.get_logger().info("  Status: {} (tolerance: ±{} m)".format(
            "PASSED" if position_passed else "✗ FAILED", 
            self.tolerance_position
        ))
        
        self.get_logger().info("\n")
        self.get_logger().info("Yaw Error: {:.2f}°".format(math.degrees(yaw_error)))
        self.get_logger().info("  Status: {} (tolerance: ±{:.1f}°)".format(
            "PASSED" if yaw_passed else "✗ FAILED", 
            math.degrees(self.tolerance_yaw)
        ))
        
        self.get_logger().info("\n")
        self.get_logger().info("Overall Status: {}".format(status))
        self.get_logger().info("\n")



def main(args=None):
    rclpy.init(args=args)
    node = CircleTestNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        rclpy.shutdown()

if __name__ == '__main__':
    main()
