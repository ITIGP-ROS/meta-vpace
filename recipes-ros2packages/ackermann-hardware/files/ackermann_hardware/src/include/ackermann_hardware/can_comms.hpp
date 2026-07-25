#ifndef CAN_COMMS_HPP
#define CAN_COMMS_HPP

#include <linux/can.h>
#include <linux/can/raw.h>
#include <net/if.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <string>
#include <iostream>

extern "C" {
#include "v_pace_db.h"
}

class CanComms
{
public:
    CanComms() = default;
    ~CanComms() { disconnect(); }

    bool connect(const std::string &interface);
    void disconnect();
    bool connected() const;

    void send_hardware_reset();
    bool read_sensor_values(int &l_enc, int &r_enc, double imu[6], bool &is_imu_reset);
    void set_motor_values(float left_vel, float right_vel);
    void set_steering(float steer_angle);

private:
    int socket_fd_ = -1;
    uint8_t expected_seq_ = 0;
    bool seq_initialized_ = false;
};

#endif
