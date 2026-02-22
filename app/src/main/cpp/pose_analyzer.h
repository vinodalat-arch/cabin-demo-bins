#pragma once

#include <memory>
#include <string>
#include <vector>
#include <android/asset_manager.h>
#include <onnxruntime_cxx_api.h>

#include "yolo_utils.h"

namespace incabin {

struct OverlayKeypoint {
    float x = 0.0f;
    float y = 0.0f;
    float conf = 0.0f;
};

struct OverlayPerson {
    float x1 = 0.0f;
    float y1 = 0.0f;
    float x2 = 0.0f;
    float y2 = 0.0f;
    float confidence = 0.0f;
    bool is_driver = false;
    OverlayKeypoint keypoints[NUM_KEYPOINTS];
};

struct PoseResult {
    int passenger_count = 0;
    int child_count = 0;
    bool driver_detected = true;
    bool driver_using_phone = false;
    bool dangerous_posture = false;
    bool child_present = false;
    bool child_slouching = false;
    bool driver_eating_drinking = false;
    std::vector<OverlayPerson> persons;

    /**
     * Serialize to JSON string.
     */
    std::string toJson() const;
};

class PoseAnalyzer {
public:
    /**
     * Construct PoseAnalyzer, loading ONNX models from Android assets.
     *
     * @param asset_manager    Android AAssetManager for reading model files
     * @param num_threads      Number of ONNX Runtime intra-op threads
     * @param thread_affinity  Thread affinity string (e.g. "4;5;6"), empty for no pinning
     */
    PoseAnalyzer(AAssetManager* asset_manager, int num_threads, const std::string& thread_affinity);

    ~PoseAnalyzer() = default;

    // Non-copyable
    PoseAnalyzer(const PoseAnalyzer&) = delete;
    PoseAnalyzer& operator=(const PoseAnalyzer&) = delete;

    // Movable
    PoseAnalyzer(PoseAnalyzer&&) = default;
    PoseAnalyzer& operator=(PoseAnalyzer&&) = default;

    /**
     * Run full pose analysis pipeline on a BGR frame.
     *
     * @param bgr_data     BGR pixel data (HWC uint8)
     * @param width        Frame width
     * @param height       Frame height
     * @param seat_on_left true if driver seat is on the left side of the frame
     * @return PoseResult with all pose-derived fields
     */
    PoseResult analyze(const uint8_t* bgr_data, int width, int height, bool seat_on_left = true);

private:
    // ONNX Runtime environment and sessions
    Ort::Env env_;
    Ort::SessionOptions session_options_;
    std::unique_ptr<Ort::Session> pose_session_;
    std::unique_ptr<Ort::Session> detect_session_;

    // Pre-allocated buffers to avoid per-frame allocation
    std::vector<uint8_t> letterbox_buf_;    // 640*640*3 (pose path)
    std::vector<float> input_tensor_buf_;   // 3*640*640 (pose path)

    // C6: Pre-allocated buffers for detect model path (avoids ~6MB alloc per call)
    std::vector<uint8_t> detect_letterbox_buf_;  // 640*640*3
    std::vector<float> detect_tensor_buf_;       // 3*640*640

    // M1: Pre-allocated buffer for crop extraction (avoids per-crop allocation)
    std::vector<uint8_t> crop_buf_;

    /**
     * Load an ONNX model from Android assets into memory.
     */
    std::vector<uint8_t> loadModelFromAssets(AAssetManager* mgr, const char* filename);

    /**
     * Run the pose model on the full frame.
     * @return Vector of person detections with keypoints.
     */
    std::vector<Detection> runPoseModel(const uint8_t* bgr_data, int width, int height);

    /**
     * Run the detection model on a crop (ROI).
     * @param crop_bgr     BGR crop data
     * @param crop_w       Crop width
     * @param crop_h       Crop height
     * @param conf_thresh  Confidence threshold (default YOLO_CONFIDENCE)
     * @return Vector of object detections
     */
    std::vector<Detection> runDetectModel(const uint8_t* crop_bgr, int crop_w, int crop_h,
                                          float conf_thresh = YOLO_CONFIDENCE);

    /**
     * Check posture: torso lean, head droop, head turn, face visibility.
     * @param kps       Keypoints array (17)
     * @param threshold Lean threshold in degrees
     * @return true if dangerous posture detected
     */
    static bool checkPosture(const std::array<Keypoint, NUM_KEYPOINTS>& kps,
                             float threshold_deg);

    /**
     * Detect objects in a crop of the original frame with padding.
     * @param frame_bgr     Full frame BGR data
     * @param frame_w       Frame width
     * @param frame_h       Frame height
     * @param box           Bounding box to crop around
     * @param target_classes Classes to look for
     * @param pad_ratio     Padding ratio (0.2 = 20% on each side)
     * @return true if any target class found
     */
    bool detectObjectsInCrop(const uint8_t* frame_bgr, int frame_w, int frame_h,
                             const Detection& box,
                             const std::vector<int>& target_classes,
                             float pad_ratio,
                             float conf_thresh = YOLO_CONFIDENCE);

    /**
     * Detect objects in an arbitrary crop region.
     * @param frame_bgr  Full frame BGR data
     * @param frame_w    Frame width
     * @param frame_h    Frame height
     * @param cx1,cy1,cx2,cy2  Crop coordinates (already clamped)
     * @param target_classes   Classes to look for
     * @param conf_thresh      Confidence threshold
     * @return true if any target class found
     */
    bool detectObjectsInRegion(const uint8_t* frame_bgr, int frame_w, int frame_h,
                               int cx1, int cy1, int cx2, int cy2,
                               const std::vector<int>& target_classes,
                               float conf_thresh = YOLO_CONFIDENCE);

    /**
     * Run inference on input tensor and return raw output.
     */
    // C7: expected_output_size validates ONNX output dimensions (0 = skip check)
    std::vector<float> runInference(Ort::Session* session,
                                    const float* input_data,
                                    const std::vector<int64_t>& input_shape,
                                    size_t expected_output_size = 0);
};

} // namespace incabin
