#!/usr/bin/env python3
"""
Description:
    Test 1: Time-based (send command for 2s, measure distance)
    Test 2: Distance-based (send command, measure time to reach 1m)

Purpose:
    Comparing Simulation and Real Robot Results for straight-line drive.
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
    vx: float = 0.0
    timestamp: float = 0.0

class OdomTestNode(Node):
    def __init__(self):
        super().__init__('odom_test_node')
        
        self.declare_parameter('velocity_command', 0.2)
        self.declare_parameter('test_type', 'time')  # 'time' or 'distance'
        self.declare_parameter('test_duration', 2.0)  # For time-based test
        self.declare_parameter('target_distance', 1.0)  # For distance-based test
        self.declare_parameter('tolerance', 0.1)
        
        self.velocity_cmd = self.get_parameter('velocity_command').value
        self.test_type = self.get_parameter('test_type').value
        self.test_duration = self.get_parameter('test_duration').value
        self.target_distance = self.get_parameter('target_distance').value
        self.tolerance = self.get_parameter('tolerance').value
   
        self.odom_data = OdomData()
        self.start_odom = OdomData()
        self.end_odom = OdomData()
        self.test_started = False
        self.test_finished = False
        self.start_time = 0.0
        self.distance_traveled = 0.0
        
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
        
        self.get_logger().info(f"Odom Test Node initialized")
        self.get_logger().info(f"  Test type: {self.test_type.upper()}")
        self.get_logger().info(f"  Velocity command: {self.velocity_cmd} m/s")
        
        if self.test_type == 'time':
            self.get_logger().info(f"  Duration: {self.test_duration} s")
            self.get_logger().info(f"  Expected distance: {self.velocity_cmd * self.test_duration:.3f} m (ideal)")
        else:
            self.get_logger().info(f"  Target distance: {self.target_distance} m")
            self.get_logger().info(f"  Expected time: {self.target_distance / self.velocity_cmd:.3f} s (ideal)")
        
        self.get_logger().info(f"  Tolerance: ±{self.tolerance}")
        self.get_logger().info("Waiting for odometry data...")
    
    def odom_callback(self, msg: Odometry):
        self.odom_data.x = msg.pose.pose.position.x
        self.odom_data.y = msg.pose.pose.position.y
        self.odom_data.vx = msg.twist.twist.linear.x
        self.odom_data.timestamp = msg.header.stamp.sec + msg.header.stamp.nanosec / 1e9
    
    def test_loop(self):
        now = self.get_clock().now().nanoseconds / 1e9
        
        # PHASE 1: Wait for odometry
        if not self.test_started:
            if self.odom_data.timestamp > 0:
                self.get_logger().info("Odometry received, starting test...")
                self.test_started = True
                self.start_time = now
                self.start_odom = OdomData(x=self.odom_data.x, y=self.odom_data.y)
            return
        
        elapsed = now - self.start_time
        self.distance_traveled = math.sqrt(
            (self.odom_data.x - self.start_odom.x)**2 + 
            (self.odom_data.y - self.start_odom.y)**2
        )
        
        # PHASE 2: Send velocity command
        if self.test_type == 'time':
            if elapsed < self.test_duration and not self.test_finished:
                self.send_velocity_command(self.velocity_cmd)
                self.get_logger().info(
                    f"  [{elapsed:.2f}/{self.test_duration:.1f}s] "
                    f"Distance: {self.distance_traveled:.4f}m, Vel: {self.odom_data.vx:.3f} m/s"
                )
            elif elapsed >= self.test_duration and not self.test_finished:
                self.send_velocity_command(0.0)
                self.end_odom = OdomData(x=self.odom_data.x, y=self.odom_data.y)
                self.test_finished = True
                self.analyze_time_based_test()
                rclpy.shutdown()
        
        else:  # distance-based
            if self.distance_traveled < self.target_distance and not self.test_finished:
                self.send_velocity_command(self.velocity_cmd)
                self.get_logger().info(
                    f"  [{self.distance_traveled:.4f}/{self.target_distance:.1f}m] "
                    f"Time: {elapsed:.2f}s, Vel: {self.odom_data.vx:.3f} m/s"
                )
            elif self.distance_traveled >= self.target_distance and not self.test_finished:
                self.send_velocity_command(0.0)
                self.end_odom = OdomData(x=self.odom_data.x, y=self.odom_data.y)
                self.test_finished = True
                self.analyze_distance_based_test(elapsed)
                rclpy.shutdown()
    
    def send_velocity_command(self, velocity):
        clock_now = self.get_clock().now().to_msg()
        command = TwistStamped()
        command.header.stamp = clock_now
        command.header.frame_id = "base_link"
        command.twist.linear.x = velocity
        self.reference_pub.publish(command)
    
    def analyze_time_based_test(self):
        dx = self.end_odom.x - self.start_odom.x
        dy = self.end_odom.y - self.start_odom.y
        distance = math.sqrt(dx**2 + dy**2)
        
        ideal_distance = self.velocity_cmd * self.test_duration
        error = distance - ideal_distance
        error_percent = (abs(error) / ideal_distance * 100) if ideal_distance > 0 else 0
        
        passed = abs(error) <= self.tolerance
        status = "PASSED" if passed else "FAILED"
        
        self.get_logger().info("TEST 1: TIME-BASED (Fixed Time, Measure Distance)")
        self.get_logger().info(f"\nCommand: {self.velocity_cmd} m/s for {self.test_duration} s")
        self.get_logger().info(f"Ideal Distance: {ideal_distance:.6f} m")
        self.get_logger().info(f"Measured Distance: {distance:.6f} m")
        self.get_logger().info(f"Error: {error:.6f} m ({error_percent:.2f}%)")
        self.get_logger().info(f"Tolerance: ±{self.tolerance} m")
        self.get_logger().info(f"Status: {status}")
    
    def analyze_distance_based_test(self, elapsed_time):
        ideal_time = self.target_distance / self.velocity_cmd
        time_error = elapsed_time - ideal_time
        time_error_percent = (abs(time_error) / ideal_time * 100) if ideal_time > 0 else 0
        
        distance = math.sqrt(
            (self.end_odom.x - self.start_odom.x)**2 + 
            (self.end_odom.y - self.start_odom.y)**2
        )
        distance_error = distance - self.target_distance
        
        passed = abs(time_error) <= (self.tolerance / self.velocity_cmd)  # Convert distance tolerance to time
        status = "PASSED" if passed else "FAILED"
        
        self.get_logger().info("TEST 2: DISTANCE-BASED (Fixed Distance, Measure Time)")
        self.get_logger().info(f"\nCommand: {self.velocity_cmd} m/s until {self.target_distance} m reached")
        self.get_logger().info(f"Ideal Time: {ideal_time:.6f} s")
        self.get_logger().info(f"Measured Time: {elapsed_time:.6f} s")
        self.get_logger().info(f"Time Error: {time_error:.6f} s ({time_error_percent:.2f}%)")
        self.get_logger().info(f"Actual Distance Traveled: {distance:.6f} m")
        self.get_logger().info(f"Distance Error: {distance_error:.6f} m")
        self.get_logger().info(f"Status: {status}")

def main(args=None):
    rclpy.init(args=args)
    node = OdomTestNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        rclpy.shutdown()

if __name__ == '__main__':
    main()
