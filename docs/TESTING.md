# การทดสอบ (Testing)

โปรเจกต์นี้แยกโค้ดที่ต้องทดสอบออกเป็น 2 กลุ่ม: **ตรรกะล้วน** (ทดสอบบน JVM ได้ ไม่ต้องมีมือถือ)
และ **งานที่ผูกกับอุปกรณ์** (ต้องทดสอบบนเครื่องจริงตาม checklist)

---

## 1. Unit test บน JVM

```bash
./gradlew :app:testDebugUnitTest            # รันทั้งหมด
./gradlew :app:testDebugUnitTest --tests '*GreenLightGateTest'   # เจาะจงชุดเดียว
```

รายงานผล: `app/build/reports/tests/testDebugUnitTest/index.html`

| ไฟล์ | เคสที่ครอบคลุม | ตรวจอะไรเป็นพิเศษ |
|------|----------------|--------------------|
| `GreenLightGateTest` | 11 เคส | เกณฑ์ต้อง **มากกว่า** 0.70 (0.70 พอดีไม่เตือน), ต้องครบ 3 เฟรมติดกัน (เฟรมที่สลับทำให้ streak รีเซ็ต), 20 เฟรมเขียวติดกันได้ alarm เดียว, cooldown 5 วิ กันเสียงรัว, re-arm หลังไฟเขียวหาย 4 เฟรม, เพิกเฉยไฟแดง/เหลือง, เลือกกรอบที่มั่นใจที่สุดในเฟรม, การ clamp ค่า threshold, `reset()` |
| `NonMaxSuppressionTest` | 7 เคส | IoU (กรอบเดียวกัน = 1.0, ไม่ทับ = 0.0, ทับครึ่ง = 1/3), ยุบกรอบซ้ำเฉพาะคลาสเดียวกัน, เรียงตาม confidence, เพดาน `maxDetections` |
| `TrafficLightLabelsTest` | 6 เคส | parse `labels.txt` (ข้ามคอมเมนต์/บรรทัดว่าง), ผูก index → คลาสด้วย **ชื่อ** ไม่ใช่ตำแหน่ง, label ที่ไม่รู้จัก → `null`, ทนตัวพิมพ์เล็ก/ใหญ่และ `-`/`_` |
| `BoxMapperTest` | 6 เคส | การแปลง `fitCenter`: aspect เท่ากัน = เต็มจอ, แถบดำบน-ล่าง, แถบดำซ้าย-ขวา, พิกัด 0..1 → pixel, กรณี View ยังไม่ถูกวัดขนาด, กรอบต้องไม่หลุดออกนอก View |
| `AcceleratorFallbackTest` | 7 เคส | ข้อความเหตุผลของ backend ที่ถูกปฏิเสธ: ใช้ `message` ของ exception, ถ้า `message` เป็น `null`/ว่างให้ใช้ชื่อคลาส, เอาเฉพาะบรรทัดแรกที่ไม่ว่าง, ตัดความยาวที่ไม่พอดีหนึ่งบรรทัด และเหตุผล**ต้องไม่ว่างเปล่า** |

**ทำไมต้องมีทั้ง 5 ชุดนี้** — เป็นตรรกะล้วนที่ตัดสินใจหรืออธิบายเรื่องสำคัญ
(จะเตือนไหม / กล่องไหนถูกวาดที่ไหน / ทำไมเครื่องนี้ตกไปรัน CPU)
และ**ไม่เกี่ยวกับ Android** จึงทดสอบได้เร็วและเสถียร ส่วนโค้ดที่เหลือ (CameraX, Interpreter, Canvas)
ถูกออกแบบให้บางที่สุดและตรวจด้วยการรันจริง

### 1.1 เทสต์ของสคริปต์ใน `tools/`

สคริปต์จัดการโมเดลก็มี unit test ของตัวเอง (ใช้แค่ standard library ไม่ต้อง `pip install`)
รันพร้อมกันได้ทั้งชุด:

```bash
python -m unittest discover -s tools/tests -v      # 91 เคส (ข้าม 6 ถ้าไม่มี TensorFlow)
```

| ไฟล์ | เคสที่ครอบคลุม | ตรวจอะไรเป็นพิเศษ |
|------|----------------|--------------------|
| `tests/test_inspect_model.py` | 25 เคส | การตัดสินว่าโมเดล "ใช้ได้กับแอป" หรือไม่ (NHWC, จำนวน output, ต้องมี tensor `[1, N, 4]`), การอ่าน quantization ทั้งรูปแบบใหม่ (`quantization_parameters`) และเก่า (`quantization` dict/tuple), การ flatten ค่าที่เป็น numpy array, การแปลง shape ที่เป็น numpy scalar ให้เป็น int ธรรมดา, การจัดรูปแบบบรรทัดสรุป และ exit code ของ CLI (ไม่มี argument = 2, ไฟล์หาย = 1) |
| `tests/test_make_dummy_model.py` | 6 เคส | ค่าเริ่มต้นของ `--output`/`--size`, การ parse flag, `--help` ต้องใช้ได้แม้ยังไม่ติดตั้ง TensorFlow |
| `tests/test_validate_dataset.py` | 24 เคส | ตัวตรวจชุดข้อมูล Pascal VOC: ภาพ/`.xml` ที่ไม่ครบคู่, XML เสีย, ชื่อคลาสที่ไม่รู้จัก, กรอบนอกภาพ/ไม่มีพื้นที่, ความสมดุลคลาส, การ normalize ชื่อ label (`" Red Light "` → `red_light`) และ exit code ของ CLI (0/1/2) |
| `tests/test_export_tflite.py` | 6 เคส | ด่านตรวจชุดข้อมูลก่อนเทรน: ชุดที่ใช้ได้ผ่าน, ภาพไม่มี annotation และกรอบนอกภาพถูกจับ, ข้อความที่บอกให้ไปรัน `validate_dataset.py`, และด่านต้องทำงาน**ก่อน** import TensorFlow (เครื่องที่ยังไม่ลง `tflite-model-maker` ก็รายงานปัญหาได้) |
| `tests/test_model_contract.py` | 6 เคส | **ต้องมี TensorFlow** — สร้างโมเดลปลอมจริงแล้วยืนยันว่ามันผ่าน `check_compatibility()`, input เป็น NHWC, มี tensor `[1, N, 4]` ตัวเดียว และรูปร่าง output ตรงสัญญา (เทียบเป็นชุด ไม่เทียบตำแหน่ง เพราะ converter อาจสลับลำดับ — ดู [MODEL.md](MODEL.md#โค้ดหาลำดับ-tensor-อย่างไร)) |
| `tests/test_check_device_backend.py` | 24 เคส | ตรรกะของด่านตรวจบนเครื่อง (หัวข้อ 1.3): อ่านบรรทัด `backend fallback — <เหตุผล>` จาก UI dump — บรรทัดที่ไม่มีเหตุผล **ไม่ถือว่าผ่าน**, ข้อความไทย/เอนทิตี XML ไม่ทำให้สแกนเพี้ยน, การเลือกอุปกรณ์จาก `adb devices` (ขึ้น `unauthorized`/`offline`/ว่าง ต้องได้คำแนะนำที่ตรงสาเหตุ), การหา `adb` จาก PATH หรือ SDK (`ANDROID_HOME`/`LOCALAPPDATA`) และคำสั่ง Gradle wrapper ของ Windows/Unix |

CI รันชุดนี้ใน job `Model tooling (python)` แยกจาก job ของ Android เพื่อไม่ให้ช้าไปด้วยกัน
(ใน CI ไม่ได้ติดตั้ง TensorFlow เคส 6 ตัวหลังจึงข้ามเอง)

ถ้าอยากให้ครบ 91 เคสในเครื่องคุณ ควรแยก venv ไว้ ไม่ให้ปนกับ Python ระบบ

```bash
python -m venv .venv                                # .venv/ ถูก gitignore ไว้แล้ว
.venv/Scripts/python.exe -m pip install "tensorflow>=2.15"   # Windows
# macOS/Linux: .venv/bin/pip install "tensorflow>=2.15"
.venv/Scripts/python.exe -m unittest discover -s tools/tests -v
```

ตรวจแล้วบนเครื่อง Windows 11 + Python 3.14 + TensorFlow 2.22.0rc0: **91/91 ผ่าน ไม่มีเคสข้าม**

### 1.2 Instrumentation test บนอุปกรณ์ (TFLite จริง)

```bash
./gradlew :app:connectedDebugAndroidTest     # ต้องต่ออุปกรณ์ผ่าน adb
```

`TrafficLightDetectorInstrumentedTest` โหลดโมเดลจาก `assets/` แล้วรันผ่าน runtime ของ TFLite จริง
(สิ่งที่ JVM test ทำไม่ได้) ตรวจ 3 เรื่อง:

| เคส | กันอะไร |
|-----|----------|
| รัน `detect()` ต่อเนื่อง 8 เฟรม (คนละภาพ) | **กันบั๊ก output buffer ไม่ rewind** — เคยทำให้เฟรมที่ 2 เป็นต้นไปพังด้วย `BufferOverflowException` เฉพาะบนเครื่องจริง |
| ป้อนเฟรมเดิมซ้ำ 4 รอบ → ผลต้องเท่ากันเป๊ะ | buffer ที่ rewind ไม่ถูกต้อง/มีข้อมูลค้าง จะให้ผลไม่คงที่ |
| ตรวจสัญญาของ `Detection` ทุกกล่อง | ช่วงค่า 0..1, กรอบไม่กลับด้าน, class index อยู่ใน `labels.txt` (0..2) |

- ข้อกำหนด: อุปกรณ์ต่อ adb อยู่ (`adb devices` ขึ้น `device`); บน **MIUI/HyperOS** ต้องเปิด "ติดตั้งผ่าน USB"
  ในตัวเลือกนักพัฒนา — และ toggle นั้นมักถูกปิดเองเป็นระยะ ถ้า `adb install` ฟ้อง `INSTALL_FAILED_USER_RESTRICTED` ให้เปิดใหม่
- หลังรันเสร็จ AGP จะ **ถอนแอปออกจากเครื่อง** (พฤติกรรมปกติ) — ติดตั้งคืนด้วย `./gradlew :app:installDebug`
- CI ไม่รันเคสนี้ (ไม่มีอุปกรณ์) — เป็นขั้นตอนก่อนทดสอบภาคสนาม; ตรวจแล้ว **3/3 ผ่าน** บน Xiaomi 23113RKC6G (Android 16)

### 1.3 ด่านตรวจ backend fallback บนเครื่อง (อัตโนมัติ)

บรรทัด `backend fallback — …` เกิดได้เฉพาะบนเครื่องจริง (unit test บน JVM สร้าง TFLite interpreter ไม่ได้)
สคริปต์นี้จึงติดตั้งแอป เปิดหน้าแอป แล้วอ่าน UI hierarchy มาตรวจว่าบรรทัดนั้นปรากฏจริง — ไม่ต้องนั่งดูจอเอง

```bash
python tools/make_dummy_model.py      # โมเดลปลอมการันตีว่า GPU ถูกปฏิเสธ จึงเห็นบรรทัดนี้แน่นอน
python tools/check_device_backend.py  # build + ติดตั้ง + เปิดแอป + dump UI + ตรวจบรรทัด
```

| ทางออก | ความหมาย |
|--------|----------|
| `0` | เห็นบรรทัด `backend fallback — <เหตุผล>` และเหตุผลไม่ว่าง |
| `1` | ตรวจแล้วไม่เห็น (หรือเห็นแต่ไม่มีเหตุผล) — สคริปต์พิมพ์คำใบ้ต่อ |
| `2` | ปัญหาสิ่งแวดล้อม: ไม่มี `adb` / ไม่มีอุปกรณ์พร้อมใช้ / build-install ล้ม / `uiautomator dump` อ่านไม่ได้ |

- ขั้นตอนภายใน: `:app:installDebug` → `pm grant CAMERA` → `am start` → `uiautomator dump` + `adb pull`
  ทุก 2 วินาทีจนครบเวลา (`--timeout`, ค่าเริ่มต้น 30 วิ; ถ้าแอปติดตั้งอยู่แล้วใช้ `--skip-install`)
- ต้องมีอุปกรณ์ที่ `adb devices` ขึ้น `device` — บน MIUI/HyperOS เปิด "ติดตั้งผ่าน USB" ด้วย (ดูหัวข้อ 1.2)
- ใช้คู่กับโมเดลจริง: ถ้า accelerator ตัวแรกโหลดผ่าน บรรทัดนี้หายไปตามการออกแบบ สคริปต์จะรายงานว่าไม่พบ
  และชี้ไปที่ `adb logcat -s TrafficLightDetector:I` เพื่อดู backend ที่เครื่องนั้นใช้จริง
- CI ไม่รันสคริปต์นี้ (ต้องมีอุปกรณ์) — เฉพาะตรรกะอ่าน UI dump ที่มีเทสต์ใน `tools/tests/` (หัวข้อ 1.1)

### สิ่งที่ unit test ยังไม่ครอบคลุม

- การโหลดโมเดลจริง + การอ่าน output tensor (ต้องมีไฟล์ `.tflite` และ runtime ของ TFLite) — เคสนี้ย้ายไปอยู่ในหัวข้อ 1.2
- การรัน inference ซ้ำหลายเฟรมบนอุปกรณ์: เจอบั๊กจริงบนเครื่อง (output buffer ไม่ถูก rewind) ตอนนี้มี `TrafficLightDetectorInstrumentedTest` คุมไว้
- `TrafficLightAnalyzer` (ต้องมี `ImageProxy`)
- `OverlayView` (ต้องมี `Canvas`) — ตรรกะพิกัดถูกย้ายไป `BoxMapper` เพื่อทดสอบแทน
- ส่วน UI ที่เหลือ (สิทธิ์กล้อง, สวิตช์/สไลเดอร์) — ตรวจด้วย checklist หัวข้อ 3

## 2. ทดสอบ pipeline ก่อนมีโมเดลจริง

```bash
python tools/make_dummy_model.py     # สร้างโมเดลปลอมที่ output ตรงสัญญา 4 tensor
./gradlew :app:assembleDebug
```

เปิดแอปแล้วควรเห็น: แอปไม่ crash, มีข้อความ "Detecting…", overlay วาดกรอบตามค่าที่โมเดลปลอมทาย
(ค่ามั่ว แต่ **ต้องมีกรอบ** และสวิตช์/สไลเดอร์ต้องทำงาน) แล้วลบโมเดลปลอมทิ้งก่อน commit

ให้สคริปต์เป็นคนตรวจว่าบรรทัด fallback ขึ้นจริงบนจอ (ไม่ต้องเพ่งจอเอง):

```bash
python tools/check_device_backend.py   # 0 = เห็นบรรทัดพร้อมเหตุผล, 1 = ไม่เห็น, 2 = ปัญหา adb/อุปกรณ์
```

ตรวจสัญญาโมเดลก่อนทุกครั้ง:

```bash
python tools/inspect_model.py app/src/main/assets/traffic_light.tflite   # ต้องได้ "Compatible"
```

## 3. Checklist ทดสอบบนเครื่องจริง

| # | ขั้นตอน | ผลที่ต้องได้ |
|---|---------|--------------|
| 1 | เปิดแอปครั้งแรก ยังไม่ให้สิทธิ์กล้อง | แสดงข้อความขอสิทธิ์ ไม่ crash |
| 2 | กด Allow | ภาพกล้องขึ้น, แถบสถานะขึ้น "Detecting…", ไม่มีกรอบ |
| 3 | ชี้ไปที่ไฟเขียว (ให้อยู่กลางภาพ ครึ่งวินาที) | กรอบสีเขียว + ป้าย `GREEN xx%` และ **เสียงบี๊บ 1 ครั้ง** |
| 4 | ปล่อยให้ไฟเขียวอยู่นาน 20-30 วินาที | **ไม่มีเสียงซ้ำ** (ป้าย/กรอบยังอัปเดต %) |
| 5 | ไฟเปลี่ยนเป็นเหลือง → แดง | กรอบเปลี่ยนสีตาม, ไม่มีเสียงเตือน |
| 6 | ไฟเขียวรอบใหม่ (หลังห่างกัน > 5 วิ) | เตือน **อีกครั้งเดียว** |
| 7 | เอามือบังไฟชั่วครู่ระหว่างไฟเขียว (1 เฟรม) | ไม่มีเสียงเพิ่ม, gate ไม่ re-arm (ไม่เตือนซ้ำ) |
| 8 | ปิดสวิตช์ "Alarm on green light" แล้วชี้ไฟเขียว | ไม่มีเสียง แต่กรอบ/ป้ายยังทำงาน และตัวนับ alarm ไม่เพิ่ม |
| 9 | ลากสไลเดอร์ไป 95% แล้วชี้ไฟเขียวไกล ๆ | เตือนยากขึ้นตามเกณฑ์ (บางกรอบจะไม่ขึ้นด้วยซ้ำ) |
| 10 | กด Home แล้วกลับเข้าแอป | กล้องกลับมาเอง, ไม่มีเสียงค้าง, ไม่ crash |
| 11 | หมุนจอเป็นแนวนอน | ภาพตั้งตรง, กรอบ **ยังตรงกับไฟ** (ไม่เหลื่อม) |
| 12 | กดย่อแอป (recents) ระหว่างเสียงดัง | เสียงหยุดทันที |
| 13 | เปิดแอปทิ้งไว้นาน 10 นาทีในรถ | ไม่ร้อนผิดปกติ, fps ยังนิ่ง, ไม่มี memory leak (ดู logcat) |

การทดสอบที่สำคัญที่สุดคือ **ข้อ 3-7** เพราะเป็นหัวใจของโจทย์ (เตือนถูกเวลา, ไม่รัว, ไม่ซ้ำ)

## 4. การวัดประสิทธิภาพ

แถบสถานะบนหน้าจอแสดง `inference ms · fps · backend · green % · alarms N` ให้แล้ว

- **inference ms** = เวลาที่ `TrafficLightDetector.detect()` ใช้ (resize + run + post-process)
- **fps** = ค่าเฉลี่ยเคลื่อนที่ของอัตราเฟรมที่วิเคราะห์ได้จริง (ไม่ใช่ fps ของกล้อง)
- **backend** = backend ที่ใช้จริง (`GPU` / `NNAPI` / `CPU x4`)
- **`backend fallback — …`** = ขึ้นเฉพาะเมื่อ backend แรก ๆ ถูกปฏิเสธ พร้อมเหตุผลสั้น ๆ
  (driver สร้าง delegate ไม่ได้ / โมเดลมี op ที่ GPU ไม่รองรับ / ขาด dependency)
  ทำให้รู้สาเหตุที่เครื่องนั้นตกไปรัน CPU ได้จากหน้าแอปเลย ไม่ต้องต่อ `adb`

แบบละเอียดด้วย logcat:

```bash
adb logcat -s TrafficLightDetector:I MainViewModel:I ToneAlerter:I
```

ตารางบันทึกผล (ให้วัดจริงบนเครื่องที่ใช้ — ตัวเลขขึ้นกับชิป, ขนาด input และ delegate มาก):

| อุปกรณ์ | โมเดล (input / quantization) | backend | inference ms | fps | อุณหภูมิ/หมายเหตุ |
|---------|------------------------------|---------|--------------|-----|-------------------|
| | | | | | |

เกร็ด: ถ้า `inference ms` มากกว่า `minIntervalMillis` (100 ms) → fps ที่ได้จะต่ำกว่าที่ตั้งไว้
ให้แก้ด้วยการลดขนาด input ของโมเดล, ใช้ INT8, หรือเปิด GPU/NNAPI

## 5. CI (GitHub Actions)

`.github/workflows/android.yml` รันทุก push/PR แยกเป็น 2 job

**job `build`** (Android, ใช้ JDK 17 + Android SDK)

1. `gradle :app:testDebugUnitTest` — unit test ทั้งหมด
2. `gradle :app:assembleDebug` — ยืนยันว่าโปรเจกต์ compile ผ่าน (ขั้นนี้คือด่านที่จับ compile error จาก Kotlin/Android APIs)
3. `gradle :app:lintDebug` — เป็น **ด่านจริง** (`abortOnError`): lint error ทำให้ CI ล้ม ไม่ถูกกลบด้วย `continue-on-error` อีก
   พร้อมพิมพ์สรุป 40 บรรทัดท้ายและอัปโหลด `lint-results-debug.{txt,html,xml}` เป็น artifact `lint-report`
4. อัปโหลด APK (`app-debug`) เป็น artifact ให้ดาวน์โหลดไปลองได้

**job `model-tools`** (Python, ไม่ต้องติดตั้ง dependency)

5. `python -m unittest discover -s tools/tests -v` — เทสต์สคริปต์ใน `tools/` (ดูหัวข้อ 1.1)

CI ไม่ได้รัน instrumented test (ต้องใช้อุปกรณ์จริง) และไม่ได้ทดสอบกล้อง
