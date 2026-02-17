#include "yolo_utils.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <numeric>

namespace incabin {

// ---- Letterbox ----

void letterbox(const uint8_t* src_bgr, int src_w, int src_h,
               uint8_t* dst_bgr, LetterboxInfo& info) {
    const int target = YOLO_INPUT_SIZE;

    // Compute scale to fit within target while maintaining aspect ratio
    float scale = std::min(static_cast<float>(target) / src_w,
                           static_cast<float>(target) / src_h);
    int new_w = static_cast<int>(src_w * scale);
    int new_h = static_cast<int>(src_h * scale);

    int pad_x = (target - new_w) / 2;
    int pad_y = (target - new_h) / 2;

    info.scale = scale;
    info.pad_x = pad_x;
    info.pad_y = pad_y;
    info.new_w = new_w;
    info.new_h = new_h;

    // Fill destination with gray (114)
    std::memset(dst_bgr, 114, target * target * 3);

    // Nearest-neighbor resize + place into padded position
    for (int dy = 0; dy < new_h; dy++) {
        int sy = static_cast<int>(dy / scale);
        if (sy >= src_h) sy = src_h - 1;

        for (int dx = 0; dx < new_w; dx++) {
            int sx = static_cast<int>(dx / scale);
            if (sx >= src_w) sx = src_w - 1;

            int src_idx = (sy * src_w + sx) * 3;
            int dst_idx = ((dy + pad_y) * target + (dx + pad_x)) * 3;

            dst_bgr[dst_idx]     = src_bgr[src_idx];
            dst_bgr[dst_idx + 1] = src_bgr[src_idx + 1];
            dst_bgr[dst_idx + 2] = src_bgr[src_idx + 2];
        }
    }
}

// ---- Preprocess ----

void preprocess(const uint8_t* bgr_640, float* out_chw) {
    const int size = YOLO_INPUT_SIZE;
    const int plane = size * size;

    // Convert BGR HWC uint8 -> RGB CHW float [0,1]
    for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
            int idx = (y * size + x) * 3;
            float b = bgr_640[idx]     / 255.0f;
            float g = bgr_640[idx + 1] / 255.0f;
            float r = bgr_640[idx + 2] / 255.0f;

            int pixel = y * size + x;
            out_chw[0 * plane + pixel] = r;  // R channel
            out_chw[1 * plane + pixel] = g;  // G channel
            out_chw[2 * plane + pixel] = b;  // B channel
        }
    }
}

// ---- IoU ----

float computeIoU(const Detection& a, const Detection& b) {
    float ix1 = std::max(a.x1, b.x1);
    float iy1 = std::max(a.y1, b.y1);
    float ix2 = std::min(a.x2, b.x2);
    float iy2 = std::min(a.y2, b.y2);

    float inter_w = std::max(0.0f, ix2 - ix1);
    float inter_h = std::max(0.0f, iy2 - iy1);
    float inter_area = inter_w * inter_h;

    float area_a = a.area();
    float area_b = b.area();
    float union_area = area_a + area_b - inter_area;

    if (union_area < 1e-6f) return 0.0f;
    return inter_area / union_area;
}

// ---- NMS ----

std::vector<Detection> nms(std::vector<Detection>& detections, float iou_thresh) {
    if (detections.empty()) return {};

    // Sort by confidence descending
    std::sort(detections.begin(), detections.end(),
              [](const Detection& a, const Detection& b) {
                  return a.confidence > b.confidence;
              });

    std::vector<bool> suppressed(detections.size(), false);
    std::vector<Detection> result;

    for (size_t i = 0; i < detections.size(); i++) {
        if (suppressed[i]) continue;
        result.push_back(detections[i]);

        for (size_t j = i + 1; j < detections.size(); j++) {
            if (suppressed[j]) continue;
            if (computeIoU(detections[i], detections[j]) > iou_thresh) {
                suppressed[j] = true;
            }
        }
    }

    return result;
}

// ---- Coordinate unscaling helpers ----

// Convert cx,cy,w,h in 640x640 letterboxed space to x1,y1,x2,y2 in original image space
static void unscaleBox(float cx, float cy, float w, float h,
                       const LetterboxInfo& info,
                       float& x1, float& y1, float& x2, float& y2) {
    // Box center-size to corners in 640 space
    float bx1 = cx - w / 2.0f;
    float by1 = cy - h / 2.0f;
    float bx2 = cx + w / 2.0f;
    float by2 = cy + h / 2.0f;

    // Remove padding, then unscale
    x1 = (bx1 - info.pad_x) / info.scale;
    y1 = (by1 - info.pad_y) / info.scale;
    x2 = (bx2 - info.pad_x) / info.scale;
    y2 = (by2 - info.pad_y) / info.scale;
}

static void unscaleKeypoint(float kx, float ky,
                            const LetterboxInfo& info,
                            float& out_x, float& out_y) {
    out_x = (kx - info.pad_x) / info.scale;
    out_y = (ky - info.pad_y) / info.scale;
}

// ---- Parse Pose Output ----

std::vector<Detection> parsePoseOutput(const float* output_data,
                                       const LetterboxInfo& info,
                                       float conf_thresh,
                                       float iou_thresh) {
    // Output shape: [1, 56, 8400] row-major
    // Transpose to [8400, 56]: element [anchor][col] = output_data[col * 8400 + anchor]
    std::vector<Detection> candidates;
    candidates.reserve(256);

    for (int a = 0; a < NUM_ANCHORS; a++) {
        float conf = output_data[4 * NUM_ANCHORS + a];  // index 4
        if (conf < conf_thresh) continue;

        Detection det;
        det.confidence = conf;
        det.class_id = 0;  // person class for pose model

        // Box: cx, cy, w, h
        float cx = output_data[0 * NUM_ANCHORS + a];
        float cy = output_data[1 * NUM_ANCHORS + a];
        float w  = output_data[2 * NUM_ANCHORS + a];
        float h  = output_data[3 * NUM_ANCHORS + a];

        unscaleBox(cx, cy, w, h, info, det.x1, det.y1, det.x2, det.y2);

        // Keypoints: 17 * 3 values starting at index 5
        for (int k = 0; k < NUM_KEYPOINTS; k++) {
            int base = (5 + k * 3);
            float kx = output_data[(base)     * NUM_ANCHORS + a];
            float ky = output_data[(base + 1) * NUM_ANCHORS + a];
            float kc = output_data[(base + 2) * NUM_ANCHORS + a];

            unscaleKeypoint(kx, ky, info, det.keypoints[k].x, det.keypoints[k].y);
            det.keypoints[k].conf = kc;
        }

        candidates.push_back(det);
    }

    return nms(candidates, iou_thresh);
}

// ---- Parse Detection Output ----

std::vector<Detection> parseDetectOutput(const float* output_data,
                                         const LetterboxInfo& info,
                                         float conf_thresh,
                                         float iou_thresh) {
    // Output shape: [1, 84, 8400] row-major
    // Transpose to [8400, 84]: element [anchor][col] = output_data[col * 8400 + anchor]
    std::vector<Detection> candidates;
    candidates.reserve(256);

    for (int a = 0; a < NUM_ANCHORS; a++) {
        // Class scores at indices 4-83
        int best_class = -1;
        float best_score = -1.0f;

        for (int c = 0; c < NUM_CLASSES; c++) {
            float score = output_data[(4 + c) * NUM_ANCHORS + a];
            if (score > best_score) {
                best_score = score;
                best_class = c;
            }
        }

        if (best_score < conf_thresh) continue;

        Detection det;
        det.confidence = best_score;
        det.class_id = best_class;

        float cx = output_data[0 * NUM_ANCHORS + a];
        float cy = output_data[1 * NUM_ANCHORS + a];
        float w  = output_data[2 * NUM_ANCHORS + a];
        float h  = output_data[3 * NUM_ANCHORS + a];

        unscaleBox(cx, cy, w, h, info, det.x1, det.y1, det.x2, det.y2);

        candidates.push_back(det);
    }

    return nms(candidates, iou_thresh);
}

} // namespace incabin
