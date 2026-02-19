#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace incabin {

/**
 * Direct V4L2 camera access for USB webcams on Honda SA8155P BSP.
 *
 * Opens a V4L2 device, configures YUYV capture, memory-maps buffers,
 * and grabs frames converted to BGR. Used when Camera2 API is unavailable
 * due to config.disable_cameraservice=true.
 */
class V4l2Camera {
public:
    /**
     * Opens device, sets YUYV format, inits mmap buffers, starts streaming.
     * If any step fails, the camera will not be open (isOpen() returns false).
     */
    V4l2Camera(const char* device_path, int width, int height);

    ~V4l2Camera();

    // Non-copyable
    V4l2Camera(const V4l2Camera&) = delete;
    V4l2Camera& operator=(const V4l2Camera&) = delete;

    bool isOpen() const;
    int width() const;
    int height() const;

    /**
     * Dequeue buffer, convert YUYV->BGR, requeue buffer.
     * Returns BGR byte vector (width * height * 3) or empty on failure.
     */
    std::vector<uint8_t> grabBgrFrame();

    /**
     * Scan /dev/video0 through /dev/video63, return the first device path
     * that supports V4L2_CAP_VIDEO_CAPTURE and V4L2_CAP_STREAMING.
     * Skips Qualcomm internal devices (cam-req-mgr, cam_sync).
     * Returns empty string if no suitable device found.
     */
    static std::string findCaptureDevice();

private:
    int fd_ = -1;
    int width_ = 0;
    int height_ = 0;

    struct MmapBuffer {
        void* start = nullptr;
        size_t length = 0;
    };
    std::vector<MmapBuffer> buffers_;
    bool streaming_ = false;

    static constexpr int NUM_BUFFERS = 2;
    static constexpr int SELECT_TIMEOUT_S = 2;

    void cleanup();
};

} // namespace incabin
