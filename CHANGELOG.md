# Changelog

รูปแบบของไฟล์นี้อ้างอิง [Keep a Changelog](https://keepachangelog.com/th/1.1.0/)
และใช้ [Semantic Versioning](https://semver.org/)

## [Unreleased]

### Planned

- Instrumented test สำหรับ `TrafficLightAnalyzer` และ `OverlayView`
- ตัวถอดรหัส anchor/NMS สำหรับโมเดล YOLO ที่ output ดิบ
- ปรับภาพก่อนเข้าโมเดล (gamma/CLAHE) สำหรับกลางคืน

## [1.2.0] — 2026-10-13

### Added

- **ปล่อย GitHub Release อัตโนมัติจาก tag** — `.github/workflows/release.yml`: push tag `v*` แล้ว CI ตรวจว่า
  tag ตรงกับ `versionName`, รันเทสต์ทั้งสองชุด, build `:app:assembleRelease` แล้วสร้าง GitHub Release แนบ
  `traffic-light-detector-<version>[-unsigned].apk` + checksum `.sha256` โดยใช้เนื้อหาจากส่วน `## [<version>]`
  ของ `CHANGELOG.md` (ผ่าน `tools/changelog_section.py` ตัวใหม่ พร้อมเทสต์ 11 เคส — ชุด `tools/tests` รวมเป็น
  102 เคส) และสั่งรันซ้ำสำหรับ tag เดิมได้ผ่าน `workflow_dispatch`
- **เซ็น APK ก่อนแนบ (ไม่บังคับ)** — `app/build.gradle.kts` อ่าน `keystore.properties` ที่ถูก gitignore
  (`storeFile`/`storePassword`/`keyAlias`/`keyPassword`): มีไฟล์ = release build ถูกเซ็น, ไม่มี = ได้ APK
  แบบ unsigned เหมือนเดิม; CI เขียนไฟล์นี้จาก signing secrets (`RELEASE_KEYSTORE_BASE64`,
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) — ขั้นตอนอยู่ใน CONTRIBUTING.md

### Changed

- เอกสาร — README เพิ่มหัวข้อ Release (ดาวน์โหลด APK + ข้อจำกัดเรื่องไฟล์ที่ยังไม่เซ็น/ไม่มีโมเดล),
  CONTRIBUTING เพิ่มขั้นตอนปล่อยเวอร์ชันและตั้งค่า signing secrets, docs/TESTING เพิ่มหัวข้อ 5.1 (release workflow)

## [1.1.0] — 2026-10-12

### Added

- **ด่านตรวจ backend บนเครื่องแบบอัตโนมัติ** — `tools/check_device_backend.py`: สร้าง+ติดตั้ง debug build,
  ให้สิทธิ์กล้อง, เปิด `MainActivity` แล้ว poll `uiautomator dump` จนเห็นบรรทัด `backend fallback — <เหตุผล>`
  บนหน้าจอจริง (exit 0 = เห็นพร้อมเหตุผลที่ไม่ว่าง, 1 = ไม่เห็น, 2 = ปัญหา adb/อุปกรณ์/build) พร้อมเทสต์ตรรกะ
  24 เคส — ใช้ยืนยันฟีเจอร์วินิจฉัย backend ได้โดยไม่ต้องนั่งดูหน้าจอมือถือ
- **วินิจฉัย backend บนหน้าจอ (ไม่ต้องต่อ adb)** — `TrafficLightDetector.create()` เก็บ backend ที่ถูก
  ปฏิเสธไว้ใน `acceleratorFallbacks` (`AcceleratorFallback` = ชื่อ backend + เหตุผลสั้น ๆ) แล้วส่งผ่าน
  `MainUiState` ขึ้นไปแสดงในแถบสถานะเป็นบรรทัด `backend fallback — GPU: <เหตุผล>` (ซ่อนเมื่อโหลดผ่าน
  ตั้งแต่ตัวแรก) พร้อมเทสต์ 7 เคส (`AcceleratorFallbackTest`) — เดิมเหตุผลถูกเขียนลง logcat อย่างเดียว
  ทำให้เครื่องที่ตกไปรัน CPU เพราะ driver/โมเดลไม่รองรับ ต้องต่อคอมเพื่อดูสาเหตุ
- **เทสต์ของสคริปต์โมเดล** — `tools/tests/` 91 เคส (ใช้แค่ standard library รันได้โดยไม่ต้อง `pip install`)
  ครอบคลุมการตัดสินความเข้ากันได้ของโมเดล, การอ่าน quantization ทั้งรูปแบบใหม่/เก่า, CLI, สัญญา output
  ของโมเดลปลอม และการตรวจชุดข้อมูล (6 เคสสุดท้ายต้องมี TensorFlow จึงจะรัน) พร้อม job `model-tools` ใน CI
- **ตัวตรวจชุดข้อมูลก่อนเทรน** — `tools/validate_dataset.py` (Pascal VOC): จับคู่ภาพกับ `.xml`,
  จับ XML เสีย, ชื่อคลาสที่ไม่รู้จัก, กรอบนอกภาพ/ไม่มีพื้นที่ และเตือนคลาสที่ไม่สมดุล — exit code 0/1/2 พร้อมเทสต์ 24 เคส
- **ด่านตรวจชุดข้อมูลใน `export_tflite.py`** — ตรวจชุดข้อมูลด้วยตัวตรวจเดียวกัน**ก่อน** import TensorFlow
  แล้วหยุดทันทีพร้อมรายการที่ต้องแก้ แทนที่จะเทรนเป็นชั่วโมงบนข้อมูลผิด (เทสต์ 6 เคส)
- **คู่มือทำโมเดลจริง** — `docs/TRAINING.md`: 6 ขั้นตั้งแต่เตรียม venv 3.9–3.11, เก็บ/label ภาพ,
  ตรวจชุดข้อมูล, เทรน/export, ติดตั้งบนเครื่อง จนถึงเกณฑ์ทดสอบภาคสนาม; README/MODEL/TESTING/TROUBLESHOOTING อัปเดตลิงก์
- **Instrumentation test บนอุปกรณ์** — `TrafficLightDetectorInstrumentedTest`: โหลดโมเดลจริงใน `assets/`,
  รัน `detect()` 8 เฟรมติดกัน (กันบั๊ก output buffer ไม่ rewind กลับมา), ป้อนเฟรมเดิมซ้ำแล้วผลต้องเท่ากัน
  และตรวจสัญญาของ `Detection`; เพิ่ม dependency `androidx.test.ext:junit` + `androidx.test:runner`
  รันด้วย `:app:connectedDebugAndroidTest` — **3/3 ผ่านบนเครื่องจริง** (Xiaomi, Android 16)
- **รายงาน lint เป็น artifact** — อัปโหลด `lint-results-debug.{txt,html,xml}` และพิมพ์สรุปใน log ของ CI

### Fixed

- **`BufferOverflowException` — "Frame dropped" ทุกเฟรมหลังเฟรมแรก (เจอจากการรันบนเครื่องจริง)**:
  TFLite เขียน output ด้วย `dst.put(...)` ซึ่งจำกัดด้วย *remaining* ของ buffer แต่โค้ดไม่ได้ `rewind()`
  output buffer ก่อนรันเฟรมใหม่ เฟรมแรกจึงผ่านแล้วเฟรมที่ 2 เป็นต้นไปล้นทุกเฟรม — แก้ใน
  `TrafficLightDetector.detect()` (rewind output ทุกตัวก่อนรัน) และ rewind input ให้ชัดเจนทุกเส้นทาง
- **GPU delegate สร้างไม่ได้ตั้งแต่ TensorFlow Lite 2.16**: `GpuDelegate` อ้าง
  `GpuDelegateFactory$Options` ซึ่งอยู่ใน artifact `tensorflow-lite-gpu-api` เท่านั้น (artifact หลักและ `-gpu`
  ไม่มีคลาสนี้) ทำให้ทุกเครื่องเจอ `NoClassDefFoundError` แล้ว fallback CPU — เพิ่ม dependency
  `tensorflow-lite-gpu-api` และยืนยันบนเครื่องจริง (Xiaomi, Android 16): โมเดล EfficientDet-Lite0 ขึ้น `backend=GPU`
- `tools/inspect_model.py` พิมพ์ shape ที่เป็น numpy scalar (`np.int32(1)`) ออกมาตรง ๆ — แก้ให้แปลงเป็น int ธรรมดา
  และเพิ่ม `shape_of()` ที่มีเทสต์คุมไว้
- เทสต์สัญญาของโมเดลปลอมเดิมยืนยันลำดับ output ตามที่ประกาศใน Keras ซึ่งไม่จริง: converter (TF 2.22)
  เก็บ subgraph output เป็น `classes, num, boxes, scores` — เปลี่ยนไปเทียบเป็นชุดของรูปร่าง
  ซึ่งเป็นวิธีเดียวกับที่ `TrafficLightDetector` และ `inspect_model.py` ใช้จริง

### Changed

- **lint กลายเป็นด่านจริง** — เอา `continue-on-error` ออกจาก `:app:lintDebug` และตั้ง `abortOnError`
  ใน `app/build.gradle.kts` (เดิม lint error จะถูกกลบแล้ว CI ยังขึ้นเขียว)
- `inspect_model.py` ตรวจว่าไฟล์โมเดลมีอยู่จริงก่อน import TensorFlow — พิมพ์ชื่อ path ที่ผิดให้เห็น
  แม้เครื่องยังไม่ได้ติดตั้ง TensorFlow (และ exit code คงที่สำหรับการทดสอบ)

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
