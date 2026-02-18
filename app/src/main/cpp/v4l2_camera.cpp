#include "v4l2_camera.h"
#include "image_utils.h"

#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <unistd.h>

#define TAG "InCabin-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace incabin {

// Helper for ioctl with retry on EINTR
static int xioctl(int fd, unsigned long request, void* arg) {
    int r;
    do {
        r = ioctl(fd, request, arg);
    } while (r == -1 && errno == EINTR);
    return r;
}

std::string V4l2Camera::findCaptureDevice() {
    LOGI("V4L2: Scanning /dev/video*...");
    char path[32];

    for (int i = 0; i < 64; i++) {
        snprintf(path, sizeof(path), "/dev/video%d", i);
        int fd = open(path, O_RDWR | O_NONBLOCK);
        if (fd < 0) {
            if (errno == EACCES) {
                LOGW("V4L2: %s: permission denied (need chmod 666)", path);
            }
            continue;
        }

        struct v4l2_capability cap;
        memset(&cap, 0, sizeof(cap));
        if (xioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) {
            close(fd);
            continue;
        }

        // Skip Qualcomm internal camera devices
        const char* card = reinterpret_cast<const char*>(cap.card);
        if (strstr(card, "cam-req-mgr") || strstr(card, "cam_sync")) {
            close(fd);
            continue;
        }

        bool has_capture = (cap.capabilities & V4L2_CAP_VIDEO_CAPTURE) != 0;
        bool has_streaming = (cap.capabilities & V4L2_CAP_STREAMING) != 0;

        // Also check device_caps if available
        if (cap.capabilities & V4L2_CAP_DEVICE_CAPS) {
            has_capture = (cap.device_caps & V4L2_CAP_VIDEO_CAPTURE) != 0;
            has_streaming = (cap.device_caps & V4L2_CAP_STREAMING) != 0;
        }

        if (has_capture && has_streaming) {
            LOGI("V4L2: Found capture device: %s (%s)", path, card);
            close(fd);
            return std::string(path);
        }

        close(fd);
    }

    LOGW("V4L2: No capture device found");
    return "";
}

V4l2Camera::V4l2Camera(const char* device_path, int width, int height)
    : width_(width), height_(height) {

    // Open device
    fd_ = open(device_path, O_RDWR);
    if (fd_ < 0) {
        LOGE("V4L2: Failed to open %s: %s", device_path, strerror(errno));
        return;
    }

    // Set format: YUYV at requested resolution
    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = width;
    fmt.fmt.pix.height = height;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUYV;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;

    if (xioctl(fd_, VIDIOC_S_FMT, &fmt) < 0) {
        LOGE("V4L2: VIDIOC_S_FMT failed: %s", strerror(errno));
        cleanup();
        return;
    }

    // Verify the driver accepted our format
    width_ = static_cast<int>(fmt.fmt.pix.width);
    height_ = static_cast<int>(fmt.fmt.pix.height);
    LOGI("V4L2: Configured YUYV %dx%d", width_, height_);

    if (fmt.fmt.pix.pixelformat != V4L2_PIX_FMT_YUYV) {
        LOGE("V4L2: Driver did not accept YUYV format");
        cleanup();
        return;
    }

    // Request mmap buffers
    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.count = NUM_BUFFERS;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;

    if (xioctl(fd_, VIDIOC_REQBUFS, &req) < 0) {
        LOGE("V4L2: VIDIOC_REQBUFS failed: %s", strerror(errno));
        cleanup();
        return;
    }

    if (req.count < 2) {
        LOGE("V4L2: Insufficient buffers (got %u)", req.count);
        cleanup();
        return;
    }

    // Map buffers
    buffers_.resize(req.count);
    for (unsigned int i = 0; i < req.count; i++) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;

        if (xioctl(fd_, VIDIOC_QUERYBUF, &buf) < 0) {
            LOGE("V4L2: VIDIOC_QUERYBUF failed for buffer %u: %s", i, strerror(errno));
            cleanup();
            return;
        }

        buffers_[i].length = buf.length;
        buffers_[i].start = mmap(nullptr, buf.length,
                                  PROT_READ | PROT_WRITE, MAP_SHARED,
                                  fd_, buf.m.offset);

        if (buffers_[i].start == MAP_FAILED) {
            LOGE("V4L2: mmap failed for buffer %u: %s", i, strerror(errno));
            buffers_[i].start = nullptr;
            cleanup();
            return;
        }
    }

    LOGI("V4L2: %u mmap buffers allocated", req.count);

    // Queue all buffers
    for (unsigned int i = 0; i < buffers_.size(); i++) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;

        if (xioctl(fd_, VIDIOC_QBUF, &buf) < 0) {
            LOGE("V4L2: VIDIOC_QBUF failed for buffer %u: %s", i, strerror(errno));
            cleanup();
            return;
        }
    }

    // Start streaming
    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd_, VIDIOC_STREAMON, &type) < 0) {
        LOGE("V4L2: VIDIOC_STREAMON failed: %s", strerror(errno));
        cleanup();
        return;
    }

    streaming_ = true;
    LOGI("V4L2: Streaming started");
}

V4l2Camera::~V4l2Camera() {
    cleanup();
}

bool V4l2Camera::isOpen() const {
    return fd_ >= 0 && streaming_;
}

int V4l2Camera::width() const {
    return width_;
}

int V4l2Camera::height() const {
    return height_;
}

std::vector<uint8_t> V4l2Camera::grabBgrFrame() {
    if (!isOpen()) return {};

    // Wait for a frame with select()
    fd_set fds;
    FD_ZERO(&fds);
    FD_SET(fd_, &fds);

    struct timeval tv;
    tv.tv_sec = SELECT_TIMEOUT_S;
    tv.tv_usec = 0;

    int r = select(fd_ + 1, &fds, nullptr, nullptr, &tv);
    if (r <= 0) {
        if (r == 0) {
            LOGE("V4L2: select() timeout (%ds)", SELECT_TIMEOUT_S);
        } else {
            LOGE("V4L2: select() error: %s", strerror(errno));
        }
        return {};
    }

    // Dequeue buffer
    struct v4l2_buffer buf;
    memset(&buf, 0, sizeof(buf));
    buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buf.memory = V4L2_MEMORY_MMAP;

    if (xioctl(fd_, VIDIOC_DQBUF, &buf) < 0) {
        LOGE("V4L2: VIDIOC_DQBUF failed: %s", strerror(errno));
        return {};
    }

    // Convert YUYV to BGR
    const auto* yuyv_data = static_cast<const uint8_t*>(buffers_[buf.index].start);
    auto bgr = yuyvToBgr(yuyv_data, width_, height_);

    // Requeue buffer
    if (xioctl(fd_, VIDIOC_QBUF, &buf) < 0) {
        LOGE("V4L2: VIDIOC_QBUF requeue failed: %s", strerror(errno));
    }

    return bgr;
}

void V4l2Camera::cleanup() {
    // Stop streaming
    if (streaming_ && fd_ >= 0) {
        enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        xioctl(fd_, VIDIOC_STREAMOFF, &type);
        streaming_ = false;
    }

    // Unmap buffers
    for (auto& b : buffers_) {
        if (b.start && b.start != MAP_FAILED) {
            munmap(b.start, b.length);
        }
    }
    buffers_.clear();

    // Close device
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
}

} // namespace incabin
