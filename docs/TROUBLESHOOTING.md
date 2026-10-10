# แก้ปัญหาที่พบบ่อย (Troubleshooting)

---

## โมเดลและการโหลด

| อาการ | สาเหตุที่เป็นไปได้ | วิธีแก้ |
|-------|---------------------|---------|
| ขึ้น **"Model error"** สีแดง | ไม่มีไฟล์ `app/src/main/assets/traffic_light.tflite` | วางไฟล์ให้ถูกที่ แล้ว build ใหม่ (asset ถูกฝังตอน build) |
| ขึ้น **"Could not create a TFLite interpreter"** | โมเดลเสียหาย/เป็น tflite ของงานอื่น | ตรวจด้วย `python tools/inspect_model.py <model>` |
| ขึ้นข้อความ **"Expected an NHWC input tensor [1, H, W, 3]"** | โมเดลรับ input คนละแบบ (เช่น NCHW หรือ multi-input) | ต้อง export ใหม่เป็น `[1, H, W, 3]` |
| ขึ้น **"The model input has dynamic dimensions"** | โมเดล export แบบ dynamic shape | export ด้วย fixed input size (เช่น 320×320) |
| ขึ้น **"No bounding-box output tensor found"** | โมเดลไม่ใช่ detection API (เช่น classification หรือ YOLO head ดิบ) | ใช้โมเดลที่มี output `[1, N, 4]` หรือเพิ่มตัว decode เอง |

## ตรวจจับไม่เจอ / เจอไม่ตรง

| อาการ | สาเหตุที่เป็นไปได้ | วิธีแก้ |
|-------|---------------------|---------|
| **ไม่มีกรอบเลยทั้งที่ชี้ที่ไฟชัด ๆ** | ค่า input scaling ไม่ตรง (อ่าน [MODEL.md § input scaling](MODEL.md#ค่า-input-scaling)) | ลองกำหนด `Config(inputScaling = ZERO_TO_ONE)` หรือ `RAW_ZERO_TO_255` แทน `AUTO` |
| ไม่มีกรอบ | `scoreThreshold` สูงเกินไป (0.5) หรือโมเดลยังไม่ถูกเทรนกับสภาพแสงนี้ | ลด `scoreThreshold` เป็น 0.3–0.4, เก็บภาพสภาพนั้นเข้า dataset เพิ่ม |
| กรอบขึ้นแต่ **ไม่ใช่ไฟ** | โมเดล overfit กับสีแดง/เขียวทั่วไป (ป้าย, ไฟท้ายรถ) | เพิ่มตัวอย่าง negative, ลด `maxDetections`, เพิ่ม `confirmFrames` |
| เจอเฉพาะไฟระยะใกล้ | dataset มีแต่ภาพระยะใกล้ หรือ input เล็กลงมาก | เพิ่มภาพระยะไกล, ใช้ `efficientdet-lite1/2`, เพิ่ม input size |
| กลางคืนตรวจไม่เจอเลย | dataset กลางวันล้วน + รบกวนแสง | เก็บภาพกลางคืนเข้า dataset, เพิ่ม augmentation ความสว่าง/contrast (ดู [ROADMAP.md](ROADMAP.md)) |

## กรอบเหลื่อม / พิกัดเพี้ยน

| อาการ | สาเหตุ | วิธีแก้ |
|-------|--------|---------|
| กรอบเลื่อนลง/ขึ้นเป็นระยะเท่ากัน | aspect ratio ของ Preview กับ Analysis ไม่ตรงกัน | ใช้ `AspectRatioStrategy` ค่าเดียวกันทั้งสอง use case (ค่าเริ่มต้นตั้งไว้แล้ว) |
| กรอบกลับด้าน/หมุนผิด | เปลี่ยน `scaleType` ของ `PreviewView` | ให้คง `app:scaleType="fitCenter"` (คู่กับ `BoxMapper`) |
| กรอบเหลื่อมเฉพาะตอนหมุนจอ | `rotationDegrees` ไม่ถูกนำไปใช้ | ตรวจว่าเฟรมผ่าน `ImageProxyExt.toUprightBitmap()` ทุกเฟรม (และไม่ได้ลบ `Bitmap.createScaledBitmap` ก่อนหมุน) |
| กรอบถูกแต่ % เพี้ยน | โมเดล output logits แต่ใช้เป็น probability | ตั้ง `Config(scoreActivation = SIGMOID)` |
| สีเพี้ยน → แยกแดง/เขียวผิด | ใช้ YUV แล้วอ่านเฉพาะY-plane (ภาพขาวดำ) | อย่าลบ RGBA path; `CameraController` ต้องคง `OUTPUT_IMAGE_FORMAT_RGBA_8888` |

## เสียงเตือน

| อาการ | สาเหตุ | วิธีแก้ |
|-------|--------|---------|
| ไม่มีเสียง | เครื่องอยู่ในโหมดห้ามรบกวน/ปิดเสียง Alarm | ทดสอบด้วยเพลง/นาฬิกาปลุก, ลด DND |
| ไม่มีเสียง | สวิตช์ "Alarm on green light" ปิดอยู่ | เปิดสวิตช์ (ดูตัวนับ `alarms` ในแถบสถานะ) |
| ไม่มีเสียง | `ToneGenerator` สร้างไม่ได้ (vendor bug) | ดู logcat tag `ToneAlerter`; ลองลด `volumePercent` หรือเปลี่ยน `toneType` |
| เสียงรัว/ดังซ้ำ | ไฟกระพริบข้ามค่า threshold ไปมา | เพิ่ม `DEFAULT_CONFIRM_FRAMES` และ `DEFAULT_MIN_ALARM_INTERVAL_MILLIS` |
| อยากให้ดังเฉพาะรอบแรกแล้วเงียบจนไฟหาย | — | เป็นพฤติกรรมปกติของ gate; ถ้าต้องการแบบอื่น ให้แก้ `GreenLightGate` ตาม [CONFIGURATION.md](CONFIGURATION.md) |
| เสียงเบา | ระดับเสียง "Alarm" ของเครื่องต่ำ | เพิ่มระดับเสียง Alarm (ไม่ใช่มีเดีย) |

## กล้องและประสิทธิภาพ

| อาการ | สาเหตุ | วิธีแก้ |
|-------|--------|---------|
| จอดำ / ภาพไม่ขึ้น | ยังไม่ได้รับสิทธิ์กล้อง, หรือเรียก `CameraController.start()` นอก main thread | ตรวจข้อความบนจอ; ตัวควบคุมกล้องต้องถูกเรียกจาก main thread เท่านั้น |
| fps ต่ำกว่า 5 | input ของโมเดลใหญ่/โหลด CPU สูง | ลด input ของโมเดล, ใช้ INT8, เปิด GPU/NNAPI, เพิ่ม `minIntervalMillis`|
| เครื่องร้อนขึ้นเรื่อย ๆ | throttle สั้นเกินไป + ทำงานบน CPU | ใช้ preset ประหยัดแบตใน [CONFIGURATION.md](CONFIGURATION.md) |
| กล้องค้างหลังกลับมาจาก background | analyzer ไม่ถูก clear ตอน `onStop` | ตรวจว่า `CameraController.stop()` ถูกเรียกใน `onStop()` (ค่าเริ่มต้นทำแล้ว) |
| `OutOfMemoryError` | เก็บบิตแมปไว้โดยไม่ recycle | ทุกเฟรมต้อง `bitmap.recycle()` หลัง `detect()`; ห้ามส่ง Bitmap ไปให้ UI |
| แถบสถานะขึ้น backend `CPU xN` ทั้งที่อยากใช้ GPU | GPU delegate ไม่รองรับโมเดลนี้ (เช่น INT8) หรือ driver เก่า | ใช้โมเดล float32 ถ้าต้องการ GPU หรือยอมรับ CPU (INT8 บน CPU มักเร็วพอ) |

## เครื่องมือ Python

| อาการ | สาเหตุ | วิธีแก้ |
|-------|--------|---------|
| `tflite-model-maker` ติดตั้งไม่ผ่าน | Python ใหม่กว่า 3.11 | ใช้ Python 3.9–3.11 หรือเปลี่ยนไปใช้ TF Model Garden (ดู [MODEL.md](MODEL.md)) |
| `TensorFlow is required` | ยังไม่ได้ลง TF | `pip install tensorflow` (สำหรับ `inspect_model.py` / `make_dummy_model.py`) |
| `export_tflite.py` บอก `Missing dataset directory` | โครงสร้างโฟลเดอร์ไม่ตรง | ต้องมี `dataset/train/images`, `dataset/train/annotations` ตาม [MODEL.md](MODEL.md) |
| เทรนแล้วโมเดลไม่ detect อะไรเลย | label map ไม่ตรงกับ XML, หรือ class ไม่สมดุล | ตรวจ `VOC_LABEL_MAP` ให้ตรงกับ `<name>` ใน XML และกระจายคลาสให้สมดุล |

## สภาพแวดล้อมการ build (JDK / Android SDK)

ชุดที่ทดสอบผ่านจริงบน Windows 11: **JDK 17.0.20.1 + Android SDK Platform 36 + build-tools 35.0.0 + Gradle 8.11.1**
(`gradle :app:testDebugUnitTest` → 30/30 ผ่าน, `gradle :app:assembleDebug` → ได้ APK)

| อาการ | สาเหตุ | วิธีแก้ |
|-------|--------|---------|
| `Could not determine the dependencies of task ':app:testDebugUnitTest'` พร้อม `java.io.IOException: The filename, directory name, or volume label syntax is incorrect` | `sdk.dir` ใน `local.properties` ถูกเขียนแบบ escape ของ Windows (`C\:\\Users\\...\\Sdk`) แล้ว Gradle ที่รันจาก command line อ่านได้ค่าที่มี `\:` ติดอยู่ จึงเป็น path ที่ผิด | เขียนด้วย forward slash: `sdk.dir=C:/Users/<user>/AppData/Local/Android/Sdk` (เปิดโปรเจกต์ใน Android Studio ทีหลังมันจะเขียนทับให้เอง) |
| Gradle ล้มทันทีด้วย `Unsupported class file major version` / `Unsupported Java` | ตั้ง `JAVA_HOME` ไปที่ JDK ใหม่เกินไป — Android Studio รุ่นใหม่ฝัง JBR 25 ไว้ แต่ Gradle 8.11.1 + AGP 8.9 ต้องใช้ **JDK 17** | `export JAVA_HOME="C:/Program Files/Java/jdk-17.0.20.1"` หรือตั้ง Gradle JDK เป็น 17 ใน Android Studio (Settings → Build Tools → Gradle) |
| CI ล้มที่ `Failed to find package 'tools'` | แพ็กเกจ `tools` ถูกถอดออกจาก SDK repository แล้ว แต่ `android-actions/setup-android` ยังขออยู่ | ติดตั้ง `platform-tools platforms;android-36 build-tools;35.0.0` แทน (ทำไว้แล้วใน `.github/workflows/android.yml`) |
| ไม่รู้ว่าเครื่องมี SDK อะไรแล้วบ้าง | — | `"%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --list_installed` |
| ไม่มี Android Studio แต่อยาก build จาก command line | — | ดูวิธีติดตั้งด้านล่าง |

### ติดตั้ง Android SDK จาก command line

```bash
# 1) ดาวน์โหลด command-line tools จาก dl.google.com แล้ววางที่
#    %LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\
# 2) ยอมรับไลเซนส์ + ติดตั้งคอมโพเนนต์ที่โปรเจกต์ต้องใช้ (ต้องมี JDK 17 ใน JAVA_HOME)
SDKMANAGER="%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat"
"$SDKMANAGER" --licenses
"$SDKMANAGER" "platform-tools" "platforms;android-36" "build-tools;35.0.0"
# 3) ชี้ Gradle มาที่ SDK ด้วย forward slash
printf 'sdk.dir=C:/Users/<user>/AppData/Local/Android/Sdk\n' > local.properties
```

โปรเจกต์นี้ **ไม่ commit `gradle/wrapper/gradle-wrapper.jar`** จึงไม่มี `./gradlew` ติดมา — ถ้ามี Gradle ในเครื่องให้สั่ง
`gradle wrapper --gradle-version 8.11.1` ครั้งเดียวเพื่อสร้าง wrapper ขึ้นมาเอง (หรือเปิดผ่าน Android Studio)
