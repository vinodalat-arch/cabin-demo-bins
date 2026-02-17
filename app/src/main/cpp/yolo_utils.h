#pragma once

#include <cstdint>
#include <vector>
#include <array>

namespace incabin {

// ---- Constants ----
constexpr float YOLO_CONFIDENCE = 0.35f;
constexpr float YOLO_NMS_IOU = 0.45f;
constexpr float KP_CONF_THRESHOLD = 0.5f;
constexpr int YOLO_INPUT_SIZE = 640;
constexpr int NUM_KEYPOINTS = 17;
constexpr int POSE_OUTPUT_COLS = 56;    // 4 box + 1 conf + 17*3 kp
constexpr int DETECT_OUTPUT_COLS = 84;  // 4 box + 80 classes
constexpr int NUM_ANCHORS = 8400;
constexpr int NUM_CLASSES = 80;

// COCO keypoint indices
constexpr int KP_NOSE = 0;
constexpr int KP_LEFT_EYE = 1;
constexpr int KP_RIGHT_EYE = 2;
constexpr int KP_LEFT_EAR = 3;
constexpr int KP_RIGHT_EAR = 4;
constexpr int KP_LEFT_SHOULDER = 5;
constexpr int KP_RIGHT_SHOULDER = 6;
constexpr int KP_LEFT_ELBOW = 7;
constexpr int KP_RIGHT_ELBOW = 8;
constexpr int KP_LEFT_WRIST = 9;
constexpr int KP_RIGHT_WRIST = 10;
constexpr int KP_LEFT_HIP = 11;
constexpr int KP_RIGHT_HIP = 12;
constexpr int KP_LEFT_KNEE = 13;
constexpr int KP_RIGHT_KNEE = 14;
constexpr int KP_LEFT_ANKLE = 15;
constexpr int KP_RIGHT_ANKLE = 16;

// ---- Structs ----

struct Keypoint {
    float x = 0.0f;
    float y = 0.0f;
    float conf = 0.0f;
};

struct Detection {
    float x1 = 0.0f;
    float y1 = 0.0f;
    float x2 = 0.0f;
    float y2 = 0.0f;
    float confidence = 0.0f;
    int class_id = -1;
    std::array<Keypoint, NUM_KEYPOINTS> keypoints;

    float area() const { return (x2 - x1) * (y2 - y1); }
};

struct LetterboxInfo {
    float scale = 1.0f;
    int pad_x = 0;
    int pad_y = 0;
    int new_w = 0;  // scaled width before padding
    int new_h = 0;  // scaled height before padding
};

// ---- Functions ----

/**
 * Letterbox resize: scale image to fit 640x640 maintaining aspect ratio,
 * pad with gray (114,114,114).
 *
 * @param src_bgr   Source BGR image (HWC, uint8)
 * @param src_w     Source width
 * @param src_h     Source height
 * @param dst_bgr   Output 640x640 BGR image (pre-allocated, 640*640*3 bytes)
 * @param info      Output letterbox transform info for coordinate mapping
 */
void letterbox(const uint8_t* src_bgr, int src_w, int src_h,
               uint8_t* dst_bgr, LetterboxInfo& info);

/**
 * Preprocess letterboxed BGR image to float CHW tensor normalized [0,1].
 * BGR->RGB conversion included.
 *
 * @param bgr_640   Letterboxed 640x640 BGR image (HWC uint8)
 * @param out_chw   Output float buffer, size 3*640*640 (pre-allocated)
 */
void preprocess(const uint8_t* bgr_640, float* out_chw);

/**
 * Non-maximum suppression.
 *
 * @param detections  Input detections (modified in-place: cleared and replaced)
 * @param iou_thresh  IoU threshold
 * @return Filtered detections after NMS
 */
std::vector<Detection> nms(std::vector<Detection>& detections, float iou_thresh);

/**
 * Parse YOLOv8n-pose output [1,56,8400] -> detections with keypoints.
 * Applies confidence filter + NMS. Scales back to original image coords.
 *
 * @param output_data  Raw output tensor data, shape [1,56,8400] (row-major)
 * @param info         Letterbox info for coordinate unscaling
 * @param conf_thresh  Confidence threshold
 * @param iou_thresh   NMS IoU threshold
 * @return Vector of person detections with keypoints
 */
std::vector<Detection> parsePoseOutput(const float* output_data,
                                       const LetterboxInfo& info,
                                       float conf_thresh = YOLO_CONFIDENCE,
                                       float iou_thresh = YOLO_NMS_IOU);

/**
 * Parse YOLOv8n detection output [1,84,8400] -> detections with class IDs.
 * Applies confidence filter + NMS. Scales back to original or crop coords.
 *
 * @param output_data  Raw output tensor data, shape [1,84,8400] (row-major)
 * @param info         Letterbox info for coordinate unscaling
 * @param conf_thresh  Confidence threshold
 * @param iou_thresh   NMS IoU threshold
 * @return Vector of object detections
 */
std::vector<Detection> parseDetectOutput(const float* output_data,
                                         const LetterboxInfo& info,
                                         float conf_thresh = YOLO_CONFIDENCE,
                                         float iou_thresh = YOLO_NMS_IOU);

/**
 * Compute IoU between two boxes.
 */
float computeIoU(const Detection& a, const Detection& b);

} // namespace incabin
