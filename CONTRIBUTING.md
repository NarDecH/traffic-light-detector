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
python -m unittest discover -s tools/tests -v   # เทสต์สคริปต์โมเดล (ไม่ต้อง pip install)
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
- อย่า commit: `local.properties`, ไฟล์ `.tflite` ที่ยังไม่ผ่านการตรวจ, `build/`, ภาพ/ชุดข้อมูลขนาดใหญ่
  (ถ้าจะแชร์ชุดข้อมูล ให้ใส่ลิงก์ + สัญญาอนุญาตใน `docs/` แทน — `dataset/` ถูก gitignore ไว้)

### Checklist ก่อนเปิด PR

- [ ] `./gradlew :app:testDebugUnitTest` ผ่าน
- [ ] `./gradlew :app:assembleDebug` ผ่าน
- [ ] ถ้าแตะ state machine → มีเทสต์ใหม่ใน `GreenLightGateTest`
- [ ] ถ้าแตะการวาด/พิกัด → มีเทสต์ใน `BoxMapperTest` หรืออธิบายว่าทำไมทดสอบไม่ได้
- [ ] ถ้าแตะสคริปต์ Python → `python -m unittest discover -s tools/tests` ผ่าน และ `--help` ของสคริปต์ที่แตะยังใช้ได้
- [ ] อัปเดตเอกสารใน `docs/` และ `CHANGELOG.md`
- [ ] ไม่มี credential/โทเคน/คีย์ในโค้ดหรือ log

## การปล่อยเวอร์ชัน (Release)

CI ปล่อย release ให้เองจาก tag — เครื่องที่ไม่มี `gh` CLI ก็ทำได้:

1. bump `versionCode` (บวก 1) และ `versionName` ใน `app/build.gradle.kts`
2. ย้ายของใน `[Unreleased]` ของ `CHANGELOG.md` ไปเป็นหัวข้อ `## [x.y.z] — YYYY-MM-DD`
3. commit แล้ว push `main` จากนั้น push tag แบบ annotated

```bash
git tag -a v1.2.0 -m "Traffic Light Detector v1.2.0"
git push origin main v1.2.0
```

`.github/workflows/release.yml` จะตรวจว่า tag ตรงกับ `versionName`, รันเทสต์ทั้งสองชุด, build `:app:assembleRelease`
แล้วสร้าง GitHub Release แนบ APK + `.sha256` โดยใช้ส่วน `## [x.y.z]` ของ `CHANGELOG.md` เป็นเนื้อหา

- **APK จะยังไม่ถูกเซ็น** จนกว่าจะตั้งค่า signing secrets (ดูหัวข้อถัดไป) — ไฟล์ที่ยังไม่เซ็นติดตั้งตรง ๆ ไม่ได้
- **ตรวจลายเซ็นก่อนแนบไฟล์**: ถ้ามี secrets แล้ว workflow จะรัน `apksigner verify` และเทียบ SHA-256 ของ cert
  กับ fingerprint ใน keystore — ไม่ตรงหรือไม่ได้เซ็น = ล้มทั้งรอบโดยยังไม่แตะหน้า release; รอบที่ไม่มี secrets
  จะขึ้น warning แล้วแนบไฟล์ unsigned ตามเดิม
- **APK ต้อง reproducible**: workflow build สองรอบและล้มถ้าไบต์ไม่ตรง (ดูหัวข้อ "APK ที่ build ซ้ำต้องได้ไฟล์เดิม")
- ถ้าแท็กไม่มีไฟล์โมเดลใน `app/src/main/assets/` release notes จะขึ้นคำเตือนว่าแอปจะแสดง `Model error`
- สั่งรันซ้ำสำหรับ tag เดิมได้: Actions → Release → Run workflow → ใส่ tag (ใช้เมื่อเพิ่งตั้งค่า secrets) — รอบที่รันซ้ำ
  จะลบ asset เก่าที่รอบนั้นไม่ได้ผลิต (เช่นไฟล์ `-unsigned` ที่ค้างอยู่) ให้เอง

### ตั้งค่า signing secrets (ทำครั้งเดียว)

keystore เป็นความลับ — **ห้าม commit** (`*.keystore`, `*.jks`, `keystore.properties` อยู่ใน `.gitignore` แล้ว)

```bash
# 1) สร้าง keystore เก็บไว้ที่รากของรีโป (แต่ห้าม commit) -- ตั้งรหัสผ่านให้เดาไม่ออก
keytool -genkeypair -v -keystore release.keystore -alias release \
  -keyalg RSA -keysize 2048 -validity 10000

# 2) แปลงเป็น base64 บรรทัดเดียว (PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")))
base64 -w0 release.keystore > release.keystore.b64
```

ใส่ค่า 4 ตัวที่ GitHub → Settings → Secrets and variables → Actions → New repository secret:

| Secret | ค่า |
|--------|-----|
| `RELEASE_KEYSTORE_BASE64` | เนื้อหาทั้งไฟล์ของ `release.keystore.b64` |
| `RELEASE_KEYSTORE_PASSWORD` | รหัสผ่านของ keystore (`storePassword`) |
| `RELEASE_KEY_ALIAS` | alias ที่ใช้ตอนสร้าง (ตัวอย่างข้างบนคือ `release`) |
| `RELEASE_KEY_PASSWORD` | รหัสผ่านของ key (ปกติเท่ากับ `storePassword`) |

ในเครื่อง: สร้างไฟล์ `keystore.properties` ที่รากโปรเจกต์ (gitignored) แล้ว `gradle :app:assembleRelease` จะเซ็นให้เอง

```
storeFile=release.keystore
storePassword=<รหัสผ่าน>
keyAlias=release
keyPassword=<รหัสผ่าน>
```

> ถ้า release ล้มด้วย 403 / `Resource not accessible by integration` ให้ไปที่ Settings → Actions → General →
> Workflow permissions แล้วเลือก **Read and write permissions**

### APK ที่ build ซ้ำต้องได้ไฟล์เดิม (reproducible build)

checksum ที่แนบในหน้า release มีค่าเมื่อทุกคนที่ build จากแท็กเดิมได้ไฟล์เดิมเป๊ะ ๆ — CI จึง build release APK
**สองรอบในงานเดียวกัน** (รอบที่สองปิด build cache) แล้วล้มทันทีถ้าไบต์ไม่ตรง ก่อนที่จะแตะหน้า release

- ต้นเหตุที่เคยทำให้สอง build ได้ SHA-256 คนละตัวคือ **SDK dependency metadata** ที่ AGP ฝังไว้ใน APK signing block
  และ serialize แบบไม่คงที่ — [app/build.gradle.kts](app/build.gradle.kts) จึงปิดด้วย `dependenciesInfo { includeInApk = false }`
  (มีผลเฉพาะ APK; รายการ dependency ยังดูได้จากที่อื่น และจะเปิดกลับก็แค่ลบบล็อกนั้น)
- ทดสอบในเครื่อง (สองบรรทัดต้องได้ค่าเท่ากัน):

```bash
for i in 1 2; do
  gradle clean :app:assembleRelease --no-daemon --no-build-cache > /dev/null
  sha256sum app/build/outputs/apk/release/app-release.apk
done
```

- **อย่าลืม `--no-build-cache`** ไม่งั้นรอบที่สองอาจถูกเสิร์ฟจากแคชแล้วการเทียบไม่มีความหมาย
- APK เก็บ revision ของ commit ไว้ (`META-INF/version-control-info.textproto` โดย path เป็น `$PROJECT_DIR` ไม่ใช่ path จริง)
  → digest จึงเปลี่ยนตาม commit แต่คงที่สำหรับ "คอมมิต + เครื่องมือชุดเดียวกัน" (JDK 17, Gradle 8.11.1, AGP/Kotlin ตาม
  `gradle/libs.versions.toml`, build-tools ที่ AGP เลือกใช้)

### สำรอง keystore และการหมุนคีย์ (rotation)

- **สำรองทันที**: คัดลอก `release.keystore` กับรหัสผ่านออกไปเก็บนอกเครื่องและนอก repo (password manager +
  ไฟล์ที่เข้ารหัส) เพราะทั้งสองอย่างถูก gitignore และกู้คืนไม่ได้ — คีย์หาย = ออกเวอร์ชันอัปเดตทับให้ผู้ใช้เดิมไม่ได้
- **หมุนคีย์เมื่อจำเป็น** (คีย์รั่ว/หมดอายุ/ต้องส่งมอบ):
  1. สร้าง keystore ใหม่ + `base64 -w0` ตามขั้นตอนด้านบน
  2. อัปเดต secrets ทั้ง 4 ตัว (Settings → Secrets and variables → Actions)
  3. Actions → Release → Run workflow ใส่ tag เดิม
  4. ตรวจ log ขั้น `Verify the APK signature` ว่าพิมพ์ fingerprint **ใหม่** ตรงกับ cert ใน APK ที่แนบ
- ⚠️ แอปที่ติดตั้งอยู่จะอัปเดตทับไม่ได้จนกว่าผู้ใช้จะถอนตัวเก่า (signature mismatch) — ถ้าแจกผ่าน Play ให้เปิด
  **Play App Signing** เพื่อหมุนเฉพาะ upload key โดยไม่กระทบผู้ใช้
- fingerprint ของคีย์ปัจจุบัน (ข้อมูลสาธารณะ ใช้เทียบของจริงได้):
  `51:25:14:C4:A6:90:62:0B:6C:83:FC:AA:E3:5F:BE:C4:F4:69:93:9B:F4:61:40:64:D4:CD:93:44:01:9F:ED:D4`

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
