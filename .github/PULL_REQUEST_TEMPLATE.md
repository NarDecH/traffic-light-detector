## สรุปการเปลี่ยนแปลง

<!-- อธิบายสั้น ๆ ว่าแก้อะไร และเพราะอะไร (เน้น "ทำไม") -->

## ชนิดของการเปลี่ยนแปลง

- [ ] แก้บั๊ก (fix)
- [ ] ฟีเจอร์ใหม่ (feat)
- [ ] ปรับปรุงโค้ดเดิม (refactor)
- [ ] เอกสาร (docs)
- [ ] โมเดล / เครื่องมือ Python (model)
- [ ] CI / build (chore)

## การทดสอบที่รันแล้ว

- [ ] `./gradlew :app:testDebugUnitTest`
- [ ] `./gradlew :app:assembleDebug`
- [ ] ทดสอบบนเครื่องจริง (ระบุรุ่น/Android): ______________________
- [ ] `python tools/inspect_model.py <model>` (ถ้าแตะโมเดล)
- [ ] `python tools/*.py --help` (ถ้าแตะสคริปต์)

## Checklist

- [ ] วางโค้ดถูก layer (ดู [CONTRIBUTING.md](../CONTRIBUTING.md)) — View ไม่มีตรรกะการตรวจจับ
- [ ] ถ้าแตะกติกาการเตือน → มีเทสต์ใน `GreenLightGateTest` ที่ครอบคลุมเคสใหม่
- [ ] ถ้าแตะการวาด/พิกัด → มีเทสต์ใน `BoxMapperTest`
- [ ] อัปเดตเอกสารที่เกี่ยวข้อง (`README.md`, `docs/*.md`) และ `CHANGELOG.md`
- [ ] ไม่มี credential/โทเคน/ไฟล์โมเดลที่ไม่ได้ตั้งใจ commit

## ภาพ/หลักฐาน (ถ้ามี)

<!-- แนบ screenshot ของ overlay, logcat, หรือผลลัพธ์ของสคริปต์ -->
