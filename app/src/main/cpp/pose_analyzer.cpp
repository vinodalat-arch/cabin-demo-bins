#include "pose_analyzer.h"

#include <android/asset_manager.h>
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <sstream>

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

PoseAnalyzer::PoseAnalyzer(AAssetManager* asset_manager)
    : env_(ORT_LOGGING_LEVEL_WARNING, "InCabinPose"),
      letterbox_buf_(YOLO_INPUT_SIZE * YOLO_INPUT_SIZE * 3),
      input_tensor_buf_(3 * YOLO_INPUT_SIZE * YOLO_INPUT_SIZE) {

    // Configure session options
    session_options_.SetIntraOpNumThreads(4);
    session_options_.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

    // Load pose model
    auto pose_model_data = loadModelFromAssets(asset_manager, "yolov8n-pose.onnx");
    if (!pose_model_data.empty()) {
        pose_session_ = std::make_unique<Ort::Session>(
            env_, pose_model_data.data(), pose_model_data.size(), session_options_);
        LOGI("YOLOv8n-pose session created");
    } else {
        LOGE("Failed to load yolov8n-pose.onnx");
    }

    // Load detection model
    auto detect_model_data = loadModelFromAssets(asset_manager, "yolov8n.onnx");
    if (!detect_model_data.empty()) {
        detect_session_ = std::make_unique<Ort::Session>(
            env_, detect_model_data.data(), detect_model_data.size(), session_options_);
        LOGI("YOLOv8n detection session created");
    } else {
        LOGE("Failed to load yolov8n.onnx");
    }
}

// ---- Inference Helpers ----

std::vector<float> PoseAnalyzer::runInference(Ort::Session* session,
                                              const float* input_data,
                                              const std::vector<int64_t>& input_shape) {
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

    const float* output_ptr = output_tensor.GetTensorData<float>();
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

    std::vector<int64_t> input_shape = {1, 3, YOLO_INPUT_SIZE, YOLO_INPUT_SIZE};
    auto output = runInference(pose_session_.get(), input_tensor_buf_.data(), input_shape);

    return parsePoseOutput(output.data(), info);
}

// ---- Run Detection Model ----

std::vector<Detection> PoseAnalyzer::runDetectModel(const uint8_t* crop_bgr,
                                                    int crop_w, int crop_h) {
    if (!detect_session_) return {};

    // Use temporary buffers for crop (don't disturb the main buffers)
    std::vector<uint8_t> crop_letterbox(YOLO_INPUT_SIZE * YOLO_INPUT_SIZE * 3);
    std::vector<float> crop_tensor(3 * YOLO_INPUT_SIZE * YOLO_INPUT_SIZE);

    LetterboxInfo info;
    letterbox(crop_bgr, crop_w, crop_h, crop_letterbox.data(), info);
    preprocess(crop_letterbox.data(), crop_tensor.data());

    std::vector<int64_t> input_shape = {1, 3, YOLO_INPUT_SIZE, YOLO_INPUT_SIZE};
    auto output = runInference(detect_session_.get(), crop_tensor.data(), input_shape);

    return parseDetectOutput(output.data(), info);
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
        if (lean_angle > threshold_deg) return true;

        // Head droop check (nose below shoulder line)
        if (kps[KP_NOSE].conf > KP_CONF_THRESHOLD) {
            if (kps[KP_NOSE].y > shoulder_mid_y) return true;
        }
    }

    // Block 2: Head turn + face visibility (needs shoulders only)
    if (shoulders_ok) {
        float shoulder_mid_x = (kps[KP_LEFT_SHOULDER].x + kps[KP_RIGHT_SHOULDER].x) / 2.0f;
        float shoulder_width = std::abs(kps[KP_LEFT_SHOULDER].x - kps[KP_RIGHT_SHOULDER].x);

        if (shoulder_width > 1e-6f) {
            // Face not visible check
            if (kps[KP_NOSE].conf <= KP_CONF_THRESHOLD) {
                return true;
            }

            // Head turn check
            float nose_offset = std::abs(kps[KP_NOSE].x - shoulder_mid_x);
            if (nose_offset > shoulder_width * HEAD_TURN_THRESHOLD) {
                return true;
            }
        }
    }

    return false;
}

// ---- Crop + Detect Helpers ----

bool PoseAnalyzer::detectObjectsInCrop(const uint8_t* frame_bgr, int frame_w, int frame_h,
                                       const Detection& box,
                                       const std::vector<int>& target_classes,
                                       float pad_ratio) {
    float pad_x = (box.x2 - box.x1) * pad_ratio;
    float pad_y = (box.y2 - box.y1) * pad_ratio;

    int cx1 = std::max(0, static_cast<int>(box.x1 - pad_x));
    int cy1 = std::max(0, static_cast<int>(box.y1 - pad_y));
    int cx2 = std::min(frame_w, static_cast<int>(box.x2 + pad_x));
    int cy2 = std::min(frame_h, static_cast<int>(box.y2 + pad_y));

    return detectObjectsInRegion(frame_bgr, frame_w, frame_h,
                                 cx1, cy1, cx2, cy2, target_classes);
}

bool PoseAnalyzer::detectObjectsInRegion(const uint8_t* frame_bgr, int frame_w, int frame_h,
                                         int cx1, int cy1, int cx2, int cy2,
                                         const std::vector<int>& target_classes) {
    int crop_w = cx2 - cx1;
    int crop_h = cy2 - cy1;
    if (crop_w <= 0 || crop_h <= 0) return false;

    // Extract crop from frame
    std::vector<uint8_t> crop(crop_w * crop_h * 3);
    for (int y = 0; y < crop_h; y++) {
        int src_row = (cy1 + y) * frame_w * 3;
        int dst_row = y * crop_w * 3;
        for (int x = 0; x < crop_w; x++) {
            int src_idx = src_row + (cx1 + x) * 3;
            int dst_idx = dst_row + x * 3;
            crop[dst_idx]     = frame_bgr[src_idx];
            crop[dst_idx + 1] = frame_bgr[src_idx + 1];
            crop[dst_idx + 2] = frame_bgr[src_idx + 2];
        }
    }

    // Run detection on crop
    auto detections = runDetectModel(crop.data(), crop_w, crop_h);

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

PoseResult PoseAnalyzer::analyze(const uint8_t* bgr_data, int width, int height) {
    PoseResult result;

    // 1. Run pose model on full frame
    auto persons = runPoseModel(bgr_data, width, height);
    if (persons.empty()) {
        LOGI("No persons detected");
        return result;
    }

    result.passenger_count = static_cast<int>(persons.size());

    // 2. Identify driver = largest bbox by area
    int driver_idx = 0;
    float max_area = 0.0f;
    for (int i = 0; i < static_cast<int>(persons.size()); i++) {
        float area = persons[i].area();
        if (area > max_area) {
            max_area = area;
            driver_idx = i;
        }
    }

    const Detection& driver = persons[driver_idx];
    float driver_height = driver.y2 - driver.y1;

    // 3. Check driver posture
    result.dangerous_posture = checkPosture(driver.keypoints, POSTURE_LEAN_THRESHOLD);

    // 4. Check for children
    for (int i = 0; i < static_cast<int>(persons.size()); i++) {
        if (i == driver_idx) continue;

        float person_height = persons[i].y2 - persons[i].y1;
        if (driver_height < 1e-6f) continue;

        float ratio = person_height / driver_height;
        if (ratio < CHILD_BBOX_RATIO) {
            result.child_present = true;
            if (checkPosture(persons[i].keypoints, CHILD_SLOUCH_THRESHOLD)) {
                result.child_slouching = true;
            }
        }
    }

    // 5. Phone detection (two-strategy)
    // Strategy 1: driver ROI with 20% padding
    std::vector<int> phone_classes = {YOLO_PHONE_CLASS};
    if (detectObjectsInCrop(bgr_data, width, height, driver, phone_classes, 0.2f)) {
        result.driver_using_phone = true;
    } else {
        // Strategy 2: wrist crops (200x200px, no padding)
        const int half = WRIST_CROP_SIZE / 2;
        int wrist_indices[] = {KP_LEFT_WRIST, KP_RIGHT_WRIST};

        for (int wrist_idx : wrist_indices) {
            if (driver.keypoints[wrist_idx].conf < KP_CONF_THRESHOLD) continue;

            int wx = static_cast<int>(driver.keypoints[wrist_idx].x);
            int wy = static_cast<int>(driver.keypoints[wrist_idx].y);

            int cx1 = std::max(0, wx - half);
            int cy1 = std::max(0, wy - half);
            int cx2 = std::min(width, wx + half);
            int cy2 = std::min(height, wy + half);

            if (cx2 <= cx1 || cy2 <= cy1) continue;

            if (detectObjectsInRegion(bgr_data, width, height,
                                      cx1, cy1, cx2, cy2, phone_classes)) {
                result.driver_using_phone = true;
                break;
            }
        }
    }

    // 6. Food/drink detection (driver ROI with 20% padding)
    result.driver_eating_drinking = detectObjectsInCrop(
        bgr_data, width, height, driver, FOOD_DRINK_CLASSES, 0.2f);

    LOGI("PoseAnalyzer: passengers=%d, phone=%d, posture=%d, child=%d, slouch=%d, eating=%d",
         result.passenger_count, result.driver_using_phone,
         result.dangerous_posture, result.child_present,
         result.child_slouching, result.driver_eating_drinking);

    return result;
}

// ---- JSON Serialization ----

std::string PoseResult::toJson() const {
    std::ostringstream ss;
    ss << "{"
       << "\"passenger_count\":" << passenger_count << ","
       << "\"driver_using_phone\":" << (driver_using_phone ? "true" : "false") << ","
       << "\"dangerous_posture\":" << (dangerous_posture ? "true" : "false") << ","
       << "\"child_present\":" << (child_present ? "true" : "false") << ","
       << "\"child_slouching\":" << (child_slouching ? "true" : "false") << ","
       << "\"driver_eating_drinking\":" << (driver_eating_drinking ? "true" : "false")
       << "}";
    return ss.str();
}

} // namespace incabin
