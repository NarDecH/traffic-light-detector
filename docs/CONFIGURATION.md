# การปรับแต่ง (Configuration)

รวมทุกค่าที่จูนได้ในโปรเจกต์ + ผลกระทบและข้อแลกเปลี่ยน

---

## 1. กติกาการเตือน (`GreenLightGate`)

ที่ `app/src/main/java/com/example/trafficlightdetector/domain/GreenLightGate.kt`

| ค่า | ค่าเริ่มต้น | ผลเมื่อเพิ่ม | ผลเมื่อลด |
|-----|-------------|--------------|-----------|
| `DEFAULT_CONFIDENCE_THRESHOLD` | `0.70f` | เตือนเฉพาะไฟเขียวที่ชัดจริง → **พลาดไฟเขียวที่ไกล/มืด** | เตือนไวขึ้นแต่มี false positive (ไฟเขียวของป้าย/สะท้อน) |
| `DEFAULT_CONFIRM_FRAMES` | `3` | ต้องเห็นติดกันนานขึ้น → ทน noise แต่ **ตอบสนองช้าลง** (3 เฟรม @10 fps ≈ 0.3 วิ) | ไวขึ้นแต่เสี่ยง false positive |
| `DEFAULT_RELEASE_MISS_FRAMES` | `4` | ใช้เวลานานขึ้นกว่า gate จะ re-arm (ทนภาพหลุด) | re-arm เร็วขึ้น → เตือนรอบใหม่เร็วขึ้น |
| `DEFAULT_MIN_ALARM_INTERVAL_MILLIS` | `5_000` | ปลอดภัยจากเสียงรัวขึ้น | เตือนถี่ขึ้นเมื่อไฟกระพริบ/สลับ |

เปลี่ยนได้ตอนรันผ่าน ViewModel:

```kotlin
// เกณฑ์ confidence ถูกจำกัดช่วง [0.55, 0.99] เพื่อไม่ให้ตั้งค่าไร้ความหมาย
viewModel.setConfidenceThresholdPercent(85)
viewModel.setAlarmEnabled(false)   // ปิดเสียง แต่ state ของ gate ยังเดินต่อ
```

> สไลเดอร์บนหน้าจอส่งค่าเป็น `THRESHOLD_MIN_PERCENT + progress` (`MainActivity`, max = 44 → 55%..99%)
> ถ้าแก้ช่วงนี้ ต้องแก้ทั้ง `MainActivity` และ `MainViewModel` ให้ตรงกัน

## 2. Detector (`TrafficLightDetector.Config`)

| ค่า | ค่าเริ่มต้น | คำอธิบาย |
|-----|-------------|----------|
| `scoreThreshold: Float` | `0.5f` | ตัดกรอบที่ confidence ต่ำกว่านี้ทิ้ง **ก่อน** NMS (คนละตัวกับเกณฑ์เตือน 0.70) |
| `iouThreshold: Float` | `0.5f` | สองกรอบคลาสเดียวกันทับกันเกินค่านี้ → เก็บอันที่มั่นใจกว่า |
| `maxDetections: Int` | `10` | เพดานจำนวนกรอบต่อเฟรม (กัน overlay รกและกัน gate กินเวลา) |
| `numThreads: Int` | `4` | เธรดของ CPU interpreter (XNNPACK) — มากเกินไปไม่ช่วยถ้าเครื่องมีคอร์น้อย |
| `useGpuDelegate: Boolean` | `true` | ลอง GPU ก่อน (เหมาะกับโมเดล float32) — ถ้าใช้ไม่ได้จะ fallback เอง |
| `useNnApiDelegate: Boolean` | `false` | ลอง NNAPI เป็นตัวที่สอง (ดีกับโมเดล INT8 แต่บางอุปกรณ์ช้ากว่า CPU) |
| `inputScaling: InputScaling` | `AUTO` | domain ของค่าที่ป้อนเข้า input tensor ดู [MODEL.md](MODEL.md#ค่า-input-scaling) |
| `scoreActivation: ScoreActivation` | `AUTO` | ให้ `sigmoid` output คะแนนหรือไม่ (โมเดลที่ output logits ต้องใช้) |

ตัวอย่างการกำหนดค่าเองใน `MainViewModel.loadDetector()`:

```kotlin
TrafficLightDetector.create(
    context = getApplication(),
    config = TrafficLightDetector.Config(
        scoreThreshold = 0.45f,
        maxDetections = 5,
        useNnApiDelegate = true,
        // ป้อนเป็น 0..1 ตรง ๆ เมื่อ AUTO เดา domain ผิด (ดู MODEL.md)
        inputScaling = TrafficLightDetector.InputScaling.ZERO_TO_ONE,
    ),
)
```

## 3. CameraX (`CameraController`)

| ค่า | ค่าเริ่มต้น | คำอธิบาย |
|-----|-------------|----------|
| `PREVIEW_SIZE` | `1280×960` | ความละเอียดของภาพที่แสดง (ไม่กระทบความเร็ว inference) |
| `ANALYSIS_SIZE` | `640×480` | ความละเอียดที่ส่งเข้าโมเดล — ลดลงได้ถ้าอยากประหยัด CPU (โมเดลย่อภาพต่ออยู่ดี) |
| `AspectRatioStrategy` | `RATIO_4_3_FALLBACK_AUTO_STRATEGY` | ต้องเป็นค่าเดียวกันทั้ง Preview และ Analysis ไม่งั้นกรอบจะเหลื่อม |
| Backpressure | `STRATEGY_KEEP_ONLY_LATEST` | ทิ้งเฟรมเก่าแทนการต่อคิว (สำคัญมากเมื่อ inference ช้ากว่ากล้อง) |
| Output format | `OUTPUT_IMAGE_FORMAT_RGBA_8888` | ได้สีจริงโดยไม่ต้องแปลง YUV เอง (โค้ดมี YUV fallback ให้อยู่แล้ว) |

## 4. Analyzer (`TrafficLightAnalyzer`)

| ค่า | ค่าเริ่มต้น | คำอธิบาย |
|-----|-------------|----------|
| `minIntervalMillis` | `100` (≈10 fps) | throttle ระหว่างเฟรม: เพิ่มเป็น 150–200 ms เพื่อประหยัดแบต/ลดความร้อน หรือลดเหลือ 50 ms เมื่อต้องการตอบสนองเร็วขึ้น |

## 5. Overlay (`OverlayView`)

| ค่า | ค่าเริ่มต้น | คำอธิบาย |
|-----|-------------|----------|
| `boxPaint.strokeWidth` | `2.5dp` (`4dp` เมื่อ highlighted) | ความหนากรอบ |
| `bracketPaint.strokeWidth` | `5dp` | ความหนา corner bracket |
| `textPaint.textSize` | `13sp` | ขนาดตัวอักษรบนป้ายกำกับ |
| `EMPHASIS_ALPHA` | `48` | ความจางของสีที่ถมในกรอบที่กำลังเตือน |
| `CHIP_ALPHA` | `235` | ความทึบของป้ายกำกับ |
| `bracketLength` | `22dp` | ความยาวของขามุม (ถูกจำกัดไม่เกิน 1/3 ของด้านกรอบ) |

ขนาดทั้งหมดคูณด้วย `density` / `scaledDensity` แล้ว จึงไม่ต้องแยกไฟล์สำหรับจอความละเอียดต่างกัน

## 6. เสียง (`ToneAlerter`)

| ค่า | ค่าเริ่มต้น | คำอธิบาย |
|-----|-------------|----------|
| `toneType` | `ToneGenerator.TONE_PROP_BEEP2` | เปลี่ยนเป็น `TONE_CDMA_ALERT_CALL_GUARD`, `TONE_PROP_BEEP` ฯลฯ |
| `beepDurationMillis` | `600` | ความยาวต่อหนึ่งบี๊บ |
| `beepCount` | `3` | จำนวนบี๊บต่อการเตือนหนึ่งครั้ง (มีช่องว่าง 120 ms ระหว่างบี๊บ) |
| `volumePercent` | `ToneGenerator.MAX_VOLUME` | ระดับเสียงของ ToneGenerator (ยังคูณกับระดับเสียง Alarm ของเครื่อง) |
| `vibrate` | `true` | สั่นประกอบ (ต้องมีสิทธิ์ `VIBRATE` ใน AndroidManifest) |

เสียงถูกส่งผ่าน `USAGE_ALARM` → ใช้สตรีม Alarm ของเครื่อง จึงดังแม้เครื่องอยู่ในโหมดเงียบ
ถ้าต้องการเสียงพูด ให้ implement `Alerter` ตัวใหม่ด้วย `MediaPlayer`/`TextToSpeech`
(ดู [ARCHITECTURE.md § จุดขยาย](ARCHITECTURE.md#10-จุดขยาย-extension-points))

## 7. สูตร preset ที่ใช้บ่อย

**โหมดประหยัดแบต (ขับยาว)**

```kotlin
TrafficLightAnalyzer(minIntervalMillis = 200)                        // 5 fps
TrafficLightDetector.Config(numThreads = 2, useGpuDelegate = true, maxDetections = 5)
CameraController.ANALYSIS_SIZE = Size(480, 360)
```

**โหมดแม่นสุด (ไฟไกล/กลางคืน)**

```kotlin
TrafficLightAnalyzer(minIntervalMillis = 66)                         // ~15 fps
TrafficLightDetector.Config(scoreThreshold = 0.4f, maxDetections = 15, useNnApiDelegate = true)
CameraController.ANALYSIS_SIZE = Size(1280, 960)                     // โมเดลย่อเองอยู่ดี
GreenLightGate.DEFAULT_CONFIRM_FRAMES = 5                            // ทน noise กลางคืน
```

**โหมดทดสอบ/ดีบัก**

```kotlin
TrafficLightDetector.Config(useGpuDelegate = false, useNnApiDelegate = false)  // CPU เท่านั้น อ่าน log ง่าย
GreenLightGate.DEFAULT_MIN_ALARM_INTERVAL_MILLIS = 1_000                       // ได้ยินเสียงบ่อยขึ้นตอนนั่งทดสอบ
```
