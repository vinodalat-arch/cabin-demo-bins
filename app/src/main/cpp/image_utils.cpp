#include "image_utils.h"
#include <algorithm>

namespace incabin {

static inline uint8_t clamp(int v) {
    return static_cast<uint8_t>(std::max(0, std::min(255, v)));
}

std::vector<uint8_t> yuvToBgr(
    const uint8_t* y_data,
    const uint8_t* u_data,
    const uint8_t* v_data,
    int width,
    int height,
    int y_row_stride,
    int uv_row_stride,
    int uv_pixel_stride
) {
    std::vector<uint8_t> bgr(width * height * 3);

    for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
            int y_idx = row * y_row_stride + col;
            int uv_row = row / 2;
            int uv_col = col / 2;
            int uv_idx = uv_row * uv_row_stride + uv_col * uv_pixel_stride;

            int y_val = y_data[y_idx] & 0xFF;
            int u_val = (u_data[uv_idx] & 0xFF) - 128;
            int v_val = (v_data[uv_idx] & 0xFF) - 128;

            // YUV to RGB (BT.601)
            int r = y_val + ((359 * v_val) >> 8);
            int g = y_val - ((88 * u_val + 183 * v_val) >> 8);
            int b = y_val + ((454 * u_val) >> 8);

            int out_idx = (row * width + col) * 3;
            bgr[out_idx]     = clamp(b);
            bgr[out_idx + 1] = clamp(g);
            bgr[out_idx + 2] = clamp(r);
        }
    }

    return bgr;
}

} // namespace incabin
