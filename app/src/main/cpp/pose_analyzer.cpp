#include "pose_analyzer.h"

#include <android/asset_manager.h>
#include <android/log.h>
#include <cmath>
#include <cstdio>
#include <algorithm>

#define TAG "InCabin-PoseAnalyzer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace incabin {

// ---- Constants ----

static constexpr float POSTURE_LEAN_THRESHOLD = 30.0f;
static constexpr float CHILD_SLOUCH_THRESHOLD = 20.0f;
static constexpr float HEAD_TURN_THRESHOLD = 0.3f;
static constexpr float CHILD_BBOX_RATIO = 0.75f;
static constexpr int WRIST_CROP_SIZE = 200;
static constexpr int YOLO_PHONE_CLASS = 67;
static const std::vector<int> FOOD_DRINK_CLASSES = {39, 40, 41, 42, 43, 44, 45, 46, 47, 48};

// Per-class confidence thresholds (higher than default 0.35 to reduce false positives)
static constexpr float PHONE_CONFIDENCE = 0.45f;
static constexpr float FOOD_CONFIDENCE = 0.50f;

// ---- Model Loading ----

std::vector<uint8_t> PoseAnalyzer::loadModelFromAssets(AAssetManager* mgr, const char* filename) {
    AAsset* asset = AAssetManager_open(mgr, filename, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Failed to open asset: %s", filename);
        return {};
    }

    off_t size = AAsset_getLength(asset);
    std::vector<uint8_t> buffer(size);
    AAsset_read(asset, buffer.data(), size);
    AAsset_close(asset);

    LOGI("Loaded model %s: %ld bytes", filename, static_cast<long>(size));
    return buffer;
}

// ---- Constructor ----

PoseAnalyzer::PoseAnalyzer(AAssetManager* asset_manager, int num_threads,
                           const std::string& thread_affinity)
    : env_(ORT_LOGGING_LEVEL_WARNING, "InCabinPose"),
      letterbox_buf_(YOLO_INPUT_SIZE * YOLO_INPUT_SIZE * 3),
      input_tensor_buf_(3 * YOLO_INPUT_SIZE * YOLO_INPUT_SIZE),
      detect_letterbox_buf_(YOLO_INPUT_SIZE * YOLO_INPUT_SIZE * 3),
      detect_tensor_buf_(3 * YOLO_INPUT_SIZE * YOLO_INPUT_SIZE),
      crop_buf_(1280 * 720 * 3) {

    // Configure session options with platform-specific thread count
    session_options_.SetIntraOpNumThreads(num_threads);
    session_options_.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

    // Pin threads to specific cores if affinity is provided (empty = let OS schedule)
    if (!thread_affinity.empty()) {
        session_options_.AddConfigEntry("session.intra_op_thread_affinities", thread_affinity.c_str());
        LOGI("Thread affinity set: threads=%d, affinity=%s", num_threads, thread_affinity.c_str());
    } else {
        LOGI("Thread count set: %d (no core pinning)", num_threads);
    }

    // Load pose model — try FP32 first (reliable), fall back to FP16
    auto pose_model_data = loadModelFromAssets(asset_manager, "yolov8n-pose.onnx");
    if (!pose_model_data.empty()) {
        try {
            pose_session_ = std::make_unique<Ort::Session>(
                env_, pose_model_data.data(), pose_model_data.size(), session_options_);
            LOGI("YOLOv8n-pose FP32 session created");
        } catch (const Ort::Exception& e) {
            LOGE("FP32 pose session failed: %s", e.what());
            pose_session_ = nullptr;
        }
    }
    if (!pose_session_) {
        auto fp16_data = loadModelFromAssets(asset_manager, "yolov8n-pose-fp16.onnx");
        if (!fp16_data.empty()) {
            try {
                pose_session_ = std::make_unique<Ort::Session>(
                    env_, fp16_data.data(), fp16_data.size(), session_options_);
                LOGI("YOLOv8n-pose FP16 session created (fallback)");
            } catch (const Ort::Exception& e) {
                LOGE("FP16 pose session also failed: %s", e.what());
            }
        } else {
            LOGE("No pose model found (tried FP32 and FP16)");
        }
    }

    // Load detection model — try FP32 first, fall back to FP16
    auto detect_model_data = loadModelFromAssets(asset_manager, "yolov8n.onnx");
    if (!detect_model_data.empty()) {
        try {
            detect_session_ = std::make_unique<Ort::Session>(
                env_, detect_model_data.data(), detect_model_data.size(), session_options_);
            LOGI("YOLOv8n detection FP32 session created");
        } catch (const Ort::Exception& e) {
            LOGE("FP32 detect session failed: %s", e.what());
            detect_session_ = nullptr;
        }
    }
    if (!detect_session_) {
        auto fp16_data = loadModelFromAssets(asset_manager, "yolov8n-fp16.onnx");
        if (!fp16_data.empty()) {
            try {
                detect_session_ = std::make_unique<Ort::Session>(
                    env_, fp16_data.data(), fp16_data.size(), session_options_);
                LOGI("YOLOv8n detection FP16 session created (fallback)");
            } catch (const Ort::Exception& e) {
                LOGE("FP16 detect session also failed: %s", e.what());
            }
        } else {
            LOGE("No detect model found (tried FP32 and FP16)");
        }
    }
}

// ---- Inference Helpers ----

std::vector<float> PoseAnalyzer::runInference(Ort::Session* session,
                                              const float* input_data,
                                              const std::vector<int64_t>& input_shape,
                                              size_t expected_output_size) {
    Ort::AllocatorWithDefaultOptions allocator;

    // Get input/output names
    auto input_name_ptr = session->GetInputNameAllocated(0, allocator);
    auto output_name_ptr = session->GetOutputNameAllocated(0, allocator);
    const char* input_name = input_name_ptr.get();
    const char* output_name = output_name_ptr.get();

    // Create input tensor
    auto memory_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    size_t input_size = 1;
    for (auto dim : input_shape) input_size *= dim;

    Ort::Value input_tensor = Ort::Value::CreateTensor<float>(
        memory_info, const_cast<float*>(input_data), input_size,
        input_shape.data(), input_shape.size());

    // Run inference
    auto output_tensors = session->Run(
        Ort::RunOptions{nullptr},
        &input_name, &input_tensor, 1,
        &output_name, 1);

    // Copy output data
    auto& output_tensor = output_tensors[0];
    auto type_info = output_tensor.GetTensorTypeAndShapeInfo();
    auto output_shape = type_info.GetShape();
    size_t output_size = 1;
    for (auto dim : output_shape) output_size *= dim;

    // C7: Validate output size to catch model version mismatch / corruption
    if (expected_output_size > 0 && output_size != expected_output_size) {
        LOGE("ONNX output size mismatch: expected %zu, got %zu", expected_output_size, output_size);
        return {};
    }

    const float* output_ptr = output_tensor.GetTensorData<float>();
    if (!output_ptr) {
        LOGE("ONNX output tensor data is null");
        return {};
    }
    std::vector<float> result(output_ptr, output_ptr + output_size);
    return result;
}

// ---- Run Pose Model ----

std::vector<Detection> PoseAnalyzer::runPoseModel(const uint8_t* bgr_data,
                                                  int width, int height) {
    if (!pose_session_) return {};

    LetterboxInfo info;
    letterbox(bgr_data, width, height, letterbox_buf_.data(), info);
    preprocess(letterbox_buf_.data(), input_tensor_buf_.data());

    static const std::vector<int64_t> input_shape = {1, 3, YOLO_INPUT_SIZE, YOLO_INPUT_SIZE};
    constexpr size_t pose_output_size = POSE_OUTPUT_COLS * NUM_ANCHORS;  // 56*8400
    auto output = runInference(pose_session_.get(), input_tensor_buf_.data(), input_shape, pose_output_size);
    if (output.empty()) return {};

    return parsePoseOutput(output.data(), info);
}

// ---- Run Detection Model ----

std::vector<Detection> PoseAnalyzer::runDetectModel(const uint8_t* crop_bgr,
                                                    int crop_w, int crop_h,
                                                    float conf_thresh) {
    if (!detect_session_) return {};

    // C6: Use pre-allocated detect buffers instead of allocating per call
    LetterboxInfo info;
    letterbox(crop_bgr, crop_w, crop_h, detect_letterbox_buf_.data(), info);
    preprocess(detect_letterbox_buf_.data(), detect_tensor_buf_.data());

    static const std::vector<int64_t> input_shape = {1, 3, YOLO_INPUT_SIZE, YOLO_INPUT_SIZE};
    constexpr size_t detect_output_size = DETECT_OUTPUT_COLS * NUM_ANCHORS;  // 84*8400
    auto output = runInference(detect_session_.get(), detect_tensor_buf_.data(), input_shape, detect_output_size);
    if (output.empty()) return {};

    return parseDetectOutput(output.data(), info, conf_thresh);
}

// ---- Posture Check ----

bool PoseAnalyzer::checkPosture(const std::array<Keypoint, NUM_KEYPOINTS>& kps,
                                float threshold_deg) {
    bool shoulders_ok = kps[KP_LEFT_SHOULDER].conf > KP_CONF_THRESHOLD &&
                        kps[KP_RIGHT_SHOULDER].conf > KP_CONF_THRESHOLD;
    bool hips_ok = kps[KP_LEFT_HIP].conf > KP_CONF_THRESHOLD &&
                   kps[KP_RIGHT_HIP].conf > KP_CONF_THRESHOLD;

    // Block 1: Torso lean + head droop (needs shoulders AND hips)
    if (shoulders_ok && hips_ok) {
        float shoulder_mid_x = (kps[KP_LEFT_SHOULDER].x + kps[KP_RIGHT_SHOULDER].x) / 2.0f;
        float shoulder_mid_y = (kps[KP_LEFT_SHOULDER].y + kps[KP_RIGHT_SHOULDER].y) / 2.0f;
        float hip_mid_x = (kps[KP_LEFT_HIP].x + kps[KP_RIGHT_HIP].x) / 2.0f;
        float hip_mid_y = (kps[KP_LEFT_HIP].y + kps[KP_RIGHT_HIP].y) / 2.0f;

        float dx = shoulder_mid_x - hip_mid_x;
        float dy = hip_mid_y - shoulder_mid_y;  // image Y is inverted

        if (dy < 1e-6f) return true;  // shoulders at or below hips

        float lean_angle = std::atan2(std::abs(dx), dy) * 180.0f / static_cast<float>(M_PI);

        // Elbow-augmented lean: if elbows are visible, compute elbow-hip lean
        // and average with shoulder-hip lean for more robust detection
        bool left_elbow_ok = kps[KP_LEFT_ELBOW].conf > KP_CONF_THRESHOLD;
        bool right_elbow_ok = kps[KP_RIGHT_ELBOW].conf > KP_CONF_THRESHOLD;
        if (left_elbow_ok && right_elbow_ok) {
            float elbow_mid_x = (kps[KP_LEFT_ELBOW].x + kps[KP_RIGHT_ELBOW].x) / 2.0f;
            float elbow_dx = elbow_mid_x - hip_mid_x;
            float elbow_lean = std::atan2(std::abs(elbow_dx), dy) * 180.0f / static_cast<float>(M_PI);
            lean_angle = (lean_angle + elbow_lean) / 2.0f;
        }

        if (lean_angle > threshold_deg) return true;

        // Head droop check (nose below shoulder line)
        if (kps[KP_NOSE].conf > KP_CONF_THRESHOLD) {
            if (kps[KP_NOSE].y > shoulder_mid_y) return true;
        }
    }

    // Block 2: Head turn + face visibility + head droop (needs shoulders only)
    if (shoulders_ok) {
        float shoulder_mid_x = (kps[KP_LEFT_SHOULDER].x + kps[KP_RIGHT_SHOULDER].x) / 2.0f;
        float shoulder_mid_y = (kps[KP_LEFT_SHOULDER].y + kps[KP_RIGHT_SHOULDER].y) / 2.0f;
        float shoulder_width = std::abs(kps[KP_LEFT_SHOULDER].x - kps[KP_RIGHT_SHOULDER].x);

        // Head droop: nose below shoulder midline (doesn't need hips)
        if (kps[KP_NOSE].conf > KP_CONF_THRESHOLD) {
            if (kps[KP_NOSE].y > shoulder_mid_y) return true;
        }

        if (shoulder_width > 1e-6f) {
            // Face visibility: at most 1 of 3 face points visible → head down/away
            int face_points_visible = 0;
            if (kps[KP_NOSE].conf > KP_CONF_THRESHOLD) face_points_visible++;
            if (kps[KP_LEFT_EYE].conf > KP_CONF_THRESHOLD) face_points_visible++;
            if (kps[KP_RIGHT_EYE].conf > KP_CONF_THRESHOLD) face_points_visible++;

            if (face_points_visible <= 1) {
                return true;  // Most face points not visible
            }

            // Head turn check (only if nose is visible)
            if (kps[KP_NOSE].conf > KP_CONF_THRESHOLD) {
                float nose_offset = std::abs(kps[KP_NOSE].x - shoulder_mid_x);
                if (nose_offset > shoulder_width * HEAD_TURN_THRESHOLD) {
                    return true;
                }
            }
        }
    }

    return false;
}

// ---- Crop + Detect Helpers ----

bool PoseAnalyzer::detectObjectsInCrop(const uint8_t* frame_bgr, int frame_w, int frame_h,
                                       const Detection& box,
                                       const std::vector<int>& target_classes,
                                       float pad_ratio,
                                       float conf_thresh) {
    float pad_x = (box.x2 - box.x1) * pad_ratio;
    float pad_y = (box.y2 - box.y1) * pad_ratio;

    int cx1 = std::max(0, static_cast<int>(box.x1 - pad_x));
    int cy1 = std::max(0, static_cast<int>(box.y1 - pad_y));
    int cx2 = std::min(frame_w, static_cast<int>(box.x2 + pad_x));
    int cy2 = std::min(frame_h, static_cast<int>(box.y2 + pad_y));

    return detectObjectsInRegion(frame_bgr, frame_w, frame_h,
                                 cx1, cy1, cx2, cy2, target_classes, conf_thresh);
}

bool PoseAnalyzer::detectObjectsInRegion(const uint8_t* frame_bgr, int frame_w, int frame_h,
                                         int cx1, int cy1, int cx2, int cy2,
                                         const std::vector<int>& target_classes,
                                         float conf_thresh) {
    int crop_w = cx2 - cx1;
    int crop_h = cy2 - cy1;
    if (crop_w <= 0 || crop_h <= 0) return false;

    // M1: Use pre-allocated crop buffer, resize only if needed
    size_t crop_size = static_cast<size_t>(crop_w) * crop_h * 3;
    if (crop_buf_.size() < crop_size) {
        crop_buf_.resize(crop_size);
    }
    const int row_bytes = crop_w * 3;
    for (int y = 0; y < crop_h; y++) {
        const uint8_t* src = frame_bgr + ((cy1 + y) * frame_w + cx1) * 3;
        uint8_t* dst = crop_buf_.data() + y * row_bytes;
        std::memcpy(dst, src, row_bytes);
    }

    // Run detection on crop with per-class confidence threshold
    auto detections = runDetectModel(crop_buf_.data(), crop_w, crop_h, conf_thresh);

    // Check if any detection matches target classes
    for (const auto& det : detections) {
        for (int cls : target_classes) {
            if (det.class_id == cls) {
                return true;
            }
        }
    }
    return false;
}

// ---- Main Analyze Pipeline ----

PoseResult PoseAnalyzer::analyze(const uint8_t* bgr_data, int width, int height, bool seat_on_left) {
    PoseResult result;

    // 1. Run pose model on full frame
    auto persons = runPoseModel(bgr_data, width, height);
    if (persons.empty()) {
        LOGI("No persons detected");
        result.driver_detected = false;
        return result;
    }

    result.passenger_count = static_cast<int>(persons.size());

    // 2. Identify driver by seat side: largest person on the driver's side of the frame
    int driver_idx = -1;
    float max_area = 0.0f;
    float frame_mid_x = width / 2.0f;

    for (int i = 0; i < static_cast<int>(persons.size()); i++) {
        float center_x = (persons[i].x1 + persons[i].x2) / 2.0f;
        bool on_driver_side = seat_on_left ? (center_x < frame_mid_x) : (center_x >= frame_mid_x);
        if (on_driver_side) {
            float area = persons[i].area();
            if (area > max_area) {
                max_area = area;
                driver_idx = i;
            }
        }
    }

    // 2a. Populate overlay persons (always, even if driver not found)
    result.persons.resize(persons.size());
    for (int i = 0; i < static_cast<int>(persons.size()); i++) {
        auto& op = result.persons[i];
        op.x1 = persons[i].x1;
        op.y1 = persons[i].y1;
        op.x2 = persons[i].x2;
        op.y2 = persons[i].y2;
        op.confidence = persons[i].confidence;
        op.is_driver = (i == driver_idx);
        op.bad_posture = checkPosture(persons[i].keypoints, POSTURE_LEAN_THRESHOLD);
        for (int k = 0; k < NUM_KEYPOINTS; k++) {
            op.keypoints[k].x = persons[i].keypoints[k].x;
            op.keypoints[k].y = persons[i].keypoints[k].y;
            op.keypoints[k].conf = persons[i].keypoints[k].conf;
        }
    }

    // 2b. If no person on driver side, skip driver-specific detections
    if (driver_idx == -1) {
        result.driver_detected = false;
        LOGI("PoseAnalyzer: passengers=%d, driver_detected=false (no person on %s side)",
             result.passenger_count, seat_on_left ? "left" : "right");
        return result;
    }

    result.driver_detected = true;

    const Detection& driver = persons[driver_idx];
    float driver_height = driver.y2 - driver.y1;

    // 3. Check driver posture
    result.dangerous_posture = checkPosture(driver.keypoints, POSTURE_LEAN_THRESHOLD);

    // 4. Check for children
    int child_count = 0;
    for (int i = 0; i < static_cast<int>(persons.size()); i++) {
        if (i == driver_idx) continue;

        float person_height = persons[i].y2 - persons[i].y1;
        if (driver_height < 1e-6f) continue;

        float ratio = person_height / driver_height;
        if (ratio < CHILD_BBOX_RATIO) {
            child_count++;
            result.child_present = true;
            if (checkPosture(persons[i].keypoints, CHILD_SLOUCH_THRESHOLD)) {
                result.child_slouching = true;
            }
        }
    }
    result.child_count = child_count;

    // 5. Phone detection (three-strategy)
    // Strategy 1: YOLO object detection on driver ROI with 20% padding
    static const std::vector<int> phone_classes = {YOLO_PHONE_CLASS};
    if (detectObjectsInCrop(bgr_data, width, height, driver, phone_classes, 0.2f, PHONE_CONFIDENCE)) {
        result.driver_using_phone = true;
    } else {
        // Strategy 2: wrist crops (asymmetric: extends more upward to capture phone at ear)
        const int half = WRIST_CROP_SIZE / 2;
        const int crop_up = half + half / 2;   // 150px above wrist (toward ear)
        const int crop_down = half / 2;         // 50px below wrist
        int wrist_indices[] = {KP_LEFT_WRIST, KP_RIGHT_WRIST};

        for (int wrist_idx : wrist_indices) {
            if (driver.keypoints[wrist_idx].conf < KP_CONF_THRESHOLD) continue;

            int wx = static_cast<int>(driver.keypoints[wrist_idx].x);
            int wy = static_cast<int>(driver.keypoints[wrist_idx].y);

            int cx1 = std::max(0, wx - half);
            int cy1 = std::max(0, wy - crop_up);
            int cx2 = std::min(width, wx + half);
            int cy2 = std::min(height, wy + crop_down);

            if (cx2 <= cx1 || cy2 <= cy1) continue;

            if (detectObjectsInRegion(bgr_data, width, height,
                                      cx1, cy1, cx2, cy2, phone_classes, PHONE_CONFIDENCE)) {
                result.driver_using_phone = true;
                break;
            }
        }
    }
    // Strategy 3: wrist-near-ear posture (phone at ear even if YOLO can't see it)
    if (!result.driver_using_phone) {
        float shoulder_width = 0.0f;
        if (driver.keypoints[KP_LEFT_SHOULDER].conf > KP_CONF_THRESHOLD &&
            driver.keypoints[KP_RIGHT_SHOULDER].conf > KP_CONF_THRESHOLD) {
            shoulder_width = std::abs(driver.keypoints[KP_LEFT_SHOULDER].x -
                                      driver.keypoints[KP_RIGHT_SHOULDER].x);
        }
        // Use 30% of shoulder width as proximity threshold, min 40px
        float ear_thresh = std::max(40.0f, shoulder_width * 0.3f);

        int ear_indices[] = {KP_LEFT_EAR, KP_RIGHT_EAR};
        int wrist_indices_s3[] = {KP_LEFT_WRIST, KP_RIGHT_WRIST};
        for (int wrist_idx : wrist_indices_s3) {
            if (driver.keypoints[wrist_idx].conf < KP_CONF_THRESHOLD) continue;
            float wx = driver.keypoints[wrist_idx].x;
            float wy = driver.keypoints[wrist_idx].y;

            for (int ear_idx : ear_indices) {
                if (driver.keypoints[ear_idx].conf < KP_CONF_THRESHOLD) continue;
                float ex = driver.keypoints[ear_idx].x;
                float ey = driver.keypoints[ear_idx].y;

                float dist = std::sqrt((wx - ex) * (wx - ex) + (wy - ey) * (wy - ey));
                if (dist < ear_thresh) {
                    result.driver_using_phone = true;
                    LOGI("PoseAnalyzer: phone detected via wrist-near-ear (dist=%.0f, thresh=%.0f)", dist, ear_thresh);
                    break;
                }
            }
            if (result.driver_using_phone) break;
        }
    }

    // 6. Food/drink detection (driver ROI with 20% padding, higher confidence threshold)
    result.driver_eating_drinking = detectObjectsInCrop(
        bgr_data, width, height, driver, FOOD_DRINK_CLASSES, 0.2f, FOOD_CONFIDENCE);

    // 7. Hands-off-wheel detection: both wrists visible and in upper half of driver bbox
    // Uses driver bbox as reference — works even when hips/shoulders aren't detected.
    // Steering wheel is in the lower portion; wrists in upper half = hands off wheel.
    {
        const auto& lw = driver.keypoints[KP_LEFT_WRIST];
        const auto& rw = driver.keypoints[KP_RIGHT_WRIST];
        bool both_wrists_visible = lw.conf >= KP_CONF_THRESHOLD && rw.conf >= KP_CONF_THRESHOLD;
        if (both_wrists_visible) {
            float bbox_mid_y = (driver.y1 + driver.y2) / 2.0f;
            // Both wrists above the vertical midpoint of the driver bbox
            if (lw.y < bbox_mid_y && rw.y < bbox_mid_y) {
                result.hands_off_wheel = true;
            }
        }
    }

    LOGI("PoseAnalyzer: passengers=%d, driver_detected=%d, phone=%d, posture=%d, child=%d, slouch=%d, eating=%d, hands_off=%d",
         result.passenger_count, result.driver_detected, result.driver_using_phone,
         result.dangerous_posture, result.child_present,
         result.child_slouching, result.driver_eating_drinking, result.hands_off_wheel);

    return result;
}

// ---- JSON Serialization ----

std::string PoseResult::toJson() const {
    // Pre-reserve: ~200 bytes base + ~900 bytes per person (17 keypoints × ~50 bytes each)
    std::string json;
    json.reserve(256 + persons.size() * 1024);

    json += "{\"passenger_count\":";
    json += std::to_string(passenger_count);
    json += ",\"child_count\":";
    json += std::to_string(child_count);
    json += ",\"driver_detected\":";
    json += driver_detected ? "true" : "false";
    json += ",\"driver_using_phone\":";
    json += driver_using_phone ? "true" : "false";
    json += ",\"dangerous_posture\":";
    json += dangerous_posture ? "true" : "false";
    json += ",\"child_present\":";
    json += child_present ? "true" : "false";
    json += ",\"child_slouching\":";
    json += child_slouching ? "true" : "false";
    json += ",\"driver_eating_drinking\":";
    json += driver_eating_drinking ? "true" : "false";
    json += ",\"hands_off_wheel\":";
    json += hands_off_wheel ? "true" : "false";
    json += ",\"persons\":[";

    // Reusable buffer for float formatting (avoids repeated std::to_string heap allocs)
    char fbuf[32];
    for (size_t i = 0; i < persons.size(); i++) {
        if (i > 0) json += ',';
        const auto& p = persons[i];
        json += "{\"x1\":";
        snprintf(fbuf, sizeof(fbuf), "%.1f", p.x1); json += fbuf;
        json += ",\"y1\":";
        snprintf(fbuf, sizeof(fbuf), "%.1f", p.y1); json += fbuf;
        json += ",\"x2\":";
        snprintf(fbuf, sizeof(fbuf), "%.1f", p.x2); json += fbuf;
        json += ",\"y2\":";
        snprintf(fbuf, sizeof(fbuf), "%.1f", p.y2); json += fbuf;
        json += ",\"confidence\":";
        snprintf(fbuf, sizeof(fbuf), "%.4f", p.confidence); json += fbuf;
        json += ",\"is_driver\":";
        json += p.is_driver ? "true" : "false";
        json += ",\"bad_posture\":";
        json += p.bad_posture ? "true" : "false";
        json += ",\"keypoints\":[";
        for (int k = 0; k < NUM_KEYPOINTS; k++) {
            if (k > 0) json += ',';
            json += "{\"x\":";
            snprintf(fbuf, sizeof(fbuf), "%.1f", p.keypoints[k].x); json += fbuf;
            json += ",\"y\":";
            snprintf(fbuf, sizeof(fbuf), "%.1f", p.keypoints[k].y); json += fbuf;
            json += ",\"c\":";
            snprintf(fbuf, sizeof(fbuf), "%.4f", p.keypoints[k].conf); json += fbuf;
            json += '}';
        }
        json += "]}";
    }
    json += "]}";
    return json;
}

} // namespace incabin
