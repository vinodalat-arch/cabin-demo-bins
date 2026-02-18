#pragma once

#include <cstdint>
#include <vector>

namespace incabin {

/**
 * Convert YUV_420_888 (NV21-style interleaved UV or planar I420) to BGR.
 *
 * @param y_data       Y plane data
 * @param u_data       U plane data
 * @param v_data       V plane data
 * @param width        Frame width
 * @param height       Frame height
 * @param y_row_stride Row stride of Y plane
 * @param uv_row_stride Row stride of U/V planes
 * @param uv_pixel_stride Pixel stride of U/V planes (1 = planar, 2 = interleaved)
 * @return BGR byte vector (width * height * 3)
 */
std::vector<uint8_t> yuvToBgr(
    const uint8_t* y_data,
    const uint8_t* u_data,
    const uint8_t* v_data,
    int width,
    int height,
    int y_row_stride,
    int uv_row_stride,
    int uv_pixel_stride
);

/**
 * Convert YUYV (YUV 4:2:2 packed) to BGR.
 *
 * YUYV packs 2 pixels in 4 bytes: [Y0 U Y1 V].
 * Uses BT.601 color conversion (same coefficients as yuvToBgr).
 *
 * @param yuyv_data  YUYV packed data (width * height * 2 bytes)
 * @param width      Frame width (must be even)
 * @param height     Frame height
 * @return BGR byte vector (width * height * 3)
 */
std::vector<uint8_t> yuyvToBgr(
    const uint8_t* yuyv_data,
    int width,
    int height
);

} // namespace incabin
