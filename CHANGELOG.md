# Changelog

รูปแบบของไฟล์นี้อ้างอิง [Keep a Changelog](https://keepachangelog.com/th/1.1.0/)
และใช้ [Semantic Versioning](https://semver.org/)

## [Unreleased]

### Added

- **เทสต์ของสคริปต์โมเดล** — `tools/tests/` 32 เคส (ใช้แค่ standard library รันได้โดยไม่ต้อง `pip install`)
  ครอบคลุมการตัดสินความเข้ากันได้ของโมเดล, การอ่าน quantization ทั้งรูปแบบใหม่/เก่า, CLI และสัญญา output
  ของโมเดลปลอม (4 เคสหลังต้องมี TensorFlow จึงจะรัน) พร้อม job `model-tools` ใน CI
- **รายงาน lint เป็น artifact** — อัปโหลด `lint-results-debug.{txt,html,xml}` และพิมพ์สรุปใน log ของ CI

### Changed

- **lint กลายเป็นด่านจริง** — เอา `continue-on-error` ออกจาก `:app:lintDebug` และตั้ง `abortOnError`
  ใน `app/build.gradle.kts` (เดิม lint error จะถูกกลบแล้ว CI ยังขึ้นเขียว)
- `inspect_model.py` ตรวจว่าไฟล์โมเดลมีอยู่จริงก่อน import TensorFlow — พิมพ์ชื่อ path ที่ผิดให้เห็น
  แม้เครื่องยังไม่ได้ติดตั้ง TensorFlow (และ exit code คงที่สำหรับการทดสอบ)

### Planned

- Instrumented test สำหรับ `TrafficLightAnalyzer` และ `OverlayView`
- ตัวถอดรหัส anchor/NMS สำหรับโมเดล YOLO ที่ output ดิบ
- ปรับภาพก่อนเข้าโมเดล (gamma/CLAHE) สำหรับกลางคืน

## [1.0.0] — 2026-10-10

### Added

- **CameraX pipeline** — `Preview` + `ImageAnalysis` (4:3, `KEEP_ONLY_LATEST`, `OUTPUT_IMAGE_FORMAT_RGBA_8888`)
  พร้อม `ImageProxyExt` ที่แปลงเฟรมเป็นบิตแมปตั้งตรง (RGBA fast path + YUV_420_888 fallback)
- **TFLite inference** — `TrafficLightDetector` รองรับ output แบบ `TFLite_Detection_PostProcess`
  (locations/classes/scores/num_detections), dtype float32/INT8/UINT8, การเลือก backend
  GPU → NNAPI → CPU อัตโนมัติ, การเดา input scaling, การ sigmoid เมื่อ output เป็น logits และ NMS
- **3 คลาสไฟจราจร** — `red_light`, `yellow_light`, `green_light` ผูกผ่าน `labels.txt`
  (แก้ไข index ของโมเดลได้โดยไม่ต้องแก้โค้ด)
- **ระบบเตือนไฟเขียว** — เกณฑ์ confidence `> 0.70`, ยืนยัน 3 เฟรมติดกัน, เตือนครั้งเดียวต่อรอบไฟเขียว,
  re-arm หลังไฟเขียวหาย 4 เฟรม และ cooldown 5 วินาทีกันเสียงรัว (`GreenLightGate`)
- **เสียงเตือน** — `ToneAlerter` ใช้ `ToneGenerator` ผ่านสตรีม Alarm + audio focus + การสั่น
  และสามารถสลับ implementation ได้ผ่าน interface `Alerter`
- **MVVM** — `MainViewModel` + `MainUiState` ปล่อย `StateFlow` สำหรับ state และ
  `SharedFlow` สำหรับ one-shot alarm event (View ไม่มีตรรกะการตรวจจับ)
- **การวาดกรอบ Bounding Box** — `OverlayView` วาดกรอบมุมโค้ง + corner bracket + ป้ายกำกับ
  `GREEN 92%` และ `BoxMapper` แปลงพิกัด normalized เป็นพิกเซลแบบ `fitCenter`
- **UI ควบคุม** — สวิตช์เปิด/ปิดเสียง และสไลเดอร์ปรับเกณฑ์ confidence (55%–99%)
- **Unit test 30 เคส** — `GreenLightGateTest`, `NonMaxSuppressionTest`,
  `TrafficLightLabelsTest`, `BoxMapperTest`
- **เครื่องมือโมเดล** — `tools/export_tflite.py` (เทรน EfficientDet-Lite0 แบบ Pascal VOC),
  `tools/make_dummy_model.py` (โมเดลปลอมสำหรับทดสอบ pipeline), `tools/inspect_model.py`
  (ตรวจสัญญา I/O ของโมเดล + แจ้ง incompatible พร้อม exit code)
- **เอกสาร** — README, ARCHITECTURE, MODEL, CONFIGURATION, TESTING, TROUBLESHOOTING, ROADMAP
  พร้อมรูปประกอบ SVG (pipeline, layered architecture, หน้าจอแอป)
- **CI** — GitHub Actions รัน unit test + assemble + upload APK

### Fixed

- ใช้ `Interpreter.runForMultipleInputsOutputs()` แทน `run(input, output)` —
  แบบหลังเขียนผลลัพธ์เฉพาะ output index 0 ทำให้อ่านได้แค่ 1 ใน 4 tensor ของโมเดล detection
- `inspect_model.py` ใช้การเข้าถึงแบบ dict (`tensor["name"]`) ให้ตรงกับ API จริงของ
  `get_input_details()` และรองรับรูปแบบ quantization ทั้งแบบใหม่ (`quantization_parameters`)
  และแบบเก่า (tuple) ของ TensorFlow ต่างเวอร์ชัน
- ไม่แสดงค่า `quant:` สำหรับ tensor `float32` ที่มี scale = 0 (เดิมสร้างความเข้าใจผิดว่าถูก quantize)

### Security

- ทุกอย่างประมวลผลบนอุปกรณ์ ไม่มีการส่งภาพหรือผลการตรวจจับออกไปภายนอก
