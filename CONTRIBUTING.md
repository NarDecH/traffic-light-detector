# Contributing

ขอบคุณที่สนใจช่วยพัฒนาโปรเจกต์นี้ 🚦

## สิ่งแวดล้อมสำหรับพัฒนา

| เครื่องมือ | เวอร์ชัน |
|-----------|----------|
| JDK | 17 |
| Android SDK | API 36 (compileSdk), minSdk 24 |
| Gradle | 8.11.1 (ผ่าน wrapper หรือ `gradle/actions/setup-gradle` ใน CI) |
| Python (เฉพาะงานโมเดล) | 3.9–3.11 สำหรับ `tflite-model-maker`, ≥ 3.10 สำหรับ `inspect_model.py` |

```bash
git clone https://github.com/NarDecH/traffic-light-detector.git
cd traffic-light-detector
./gradlew :app:testDebugUnitTest     # unit test ต้องผ่านก่อนส่ง PR
./gradlew :app:assembleDebug         # ต้อง compile ผ่าน
```

ถ้ายังไม่มีโมเดล ให้ `python tools/make_dummy_model.py` เพื่อให้แอปรันได้ (อย่า commit ไฟล์นั้นทับโมเดลจริง)

## กฎการแบ่ง layer (สำคัญที่สุด)

โปรเจกต์นี้จัดชั้นไว้ชัดเจน ถ้าเพิ่มโค้ดใหม่ให้วางตามนี้:

| อยากเพิ่ม | วางที่ | กติกา |
|-----------|--------|-------|
| ตรรกะการตรวจจับ/ตัดสินใจ | `domain/` | ต้อง **ไม่** import `android.*` ถ้าเป็นไปได้ (เพื่อให้ unit test ได้) |
| การอ่าน/แปลงโมเดล | `ml/` | ถ้าเป็นคณิตศาสตร์ล้วน (เช่น NMS, quantization) ให้แยกออกเป็น object ที่ทดสอบได้ |
| การผูกกล้อง | `camera/` | ห้ามใส่ตรรกะการเตือนหรือ UI |
| state ของหน้าจอ | `ui/` (`MainViewModel`, `MainUiState`) | View ห้ามตัดสินใจเรื่องการตรวจจับ — แค่ `render(state)` |
| เสียง/การสั่น | `alarm/` | ต้อง implement `Alerter` เพื่อสลับได้ |

**ห้าม**: ทำให้ ViewModel รู้จัก `View`/`Canvas`, ให้ `OverlayView` รู้จัก TFLite,
หรือให้ `TrafficLightDetector` รู้จัก lifecycle ของ Activity

## สไตล์โค้ด

- Kotlin official style (`kotlin.code.style=official`), 4 spaces, บรรทัดไม่เกิน ~120 ตัวอักษร
- ตั้งชื่อให้สื่อความหมายและใช้ comment อธิบาย **ทำไม** ไม่ใช่ **อะไร** (ดูตัวอย่างใน `GreenLightGate`)
- ค่าคงที่ที่จูนได้ ให้เป็น `const val` ใน `companion object` พร้อม KDoc ที่บอกผลกระทบ
- อย่าใส่ magic number ใน UI thread loop

## การเพิ่มคลาสใหม่ (เช่น `arrow_light`)

1. เพิ่มค่าใน `TrafficLightClass` (`modelLabel`, `displayName`, `color`)
2. เพิ่มบรรทัดใน `app/src/main/assets/labels.txt` ให้ตรงกับ class index ของโมเดล
3. เพิ่มเคสใน `TrafficLightLabelsTest`
4. อัปเดต `docs/MODEL.md` และ README (ตารางคลาส/สัญญาโมเดล)

ไม่ต้องแก้ `TrafficLightDetector` — มันอ่าน label จากไฟล์อยู่แล้ว

## Commit และ PR

- commit message สั้น เน้น "ทำไม" (แนะนำ conventional commits: `feat:`, `fix:`, `docs:`, `test:`)
- 1 PR = 1 เรื่อง; ถ้าแตะทั้ง Kotlin และสคริปต์โมเดล ให้แยก PR ถ้าเป็นได้
- อย่า commit: `local.properties`, ไฟล์ `.tflite` ที่ยังไม่ผ่านการตรวจ, `build/`, ภาพ数据集ขนาดใหญ่
  (ถ้าจะแชร์ชุดข้อมูล ให้ใส่ลิงก์ + สัญญาอนุญาตใน `docs/` แทน)

### Checklist ก่อนเปิด PR

- [ ] `./gradlew :app:testDebugUnitTest` ผ่าน
- [ ] `./gradlew :app:assembleDebug` ผ่าน
- [ ] ถ้าแตะ state machine → มีเทสต์ใหม่ใน `GreenLightGateTest`
- [ ] ถ้าแตะการวาด/พิกัด → มีเทสต์ใน `BoxMapperTest` หรืออธิบายว่าทำไมทดสอบไม่ได้
- [ ] ถ้าแตะสคริปต์ Python → รัน `python tools/inspect_model.py` (หรือ `--help`) แล้วยังทำงานถูก
- [ ] อัปเดตเอกสารใน `docs/` และ `CHANGELOG.md`
- [ ] ไม่มี credential/โทเคน/คีย์ในโค้ดหรือ log

## การรายงานบั๊ก

เปิด issue พร้อมข้อมูล: อาการที่เห็น vs ที่คาดหวัง, รุ่นมือถือ + Android version,
โมเดลที่ใช้ (ขนาด input/quantization), และ logcat:

```bash
adb logcat -s TrafficLightDetector:I MainViewModel:I ToneAlerter:I
```

อาการ "ตรวจไม่เจออะไรเลย" มักมาจาก input scaling ไม่ตรง — ลองตาม
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) ก่อนแจ้ง

## สัญญาอนุญาตของการ contribute

การส่ง PR ถือว่าคุณยินยอมให้โค้ดของคุณถูกเผยแพร่ภายใต้สัญญาอนุญาตของโปรเจกต์นี้ (MIT)
