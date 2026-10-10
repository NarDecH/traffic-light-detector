# โมเดล (Model)

เอกสารนี้อธิบายว่าแอปคาดหวังอะไรจากโมเดล `.tflite`, วิธีเตรียมชุดข้อมูล, วิธีเทรน/export
และวิธีตรวจสอบก่อนเอาไปวางใน `app/src/main/assets/`

![pipeline](images/pipeline.svg)

---

## 1. สัญญา I/O ของโมเดล

`TrafficLightDetector` รองรับโมเดล **object detection** แบบ TFLite Detection API
(`TFLite_Detection_PostProcess`) เท่านั้น

**Input** — `[1, H, W, 3]` (NHWC), `H = W` ไม่จำเป็นต้องเท่ากัน แต่ต้องเป็นค่าคงที่ (ไม่ใช่ dynamic)
dtype เป็น `float32`, `uint8` หรือ `int8`

**Output** — ต้องมี 4 tensor (ตัวสุดท้ายไม่บังคับ):

| # | Tensor | Shape | ความหมาย |
|---|--------|-------|----------|
| 1 | locations | `[1, N, 4]` | `ymin, xmin, ymax, xmax` แบบ normalized 0..1 |
| 2 | classes | `[1, N]` | class index (จำนวนเต็ม) → ไปอ่านชื่อจาก `labels.txt` |
| 3 | scores | `[1, N]` | confidence 0..1 (ถ้าเป็น logits โค้ดจะ `sigmoid` ให้) |
| 4 | num_detections | `[1]` | จำนวนผลลัพธ์ที่ใช้ได้ (ไม่มีก็ได้ → ใช้ทั้ง N) |

### โค้ดหาลำดับ tensor อย่างไร

TFLite ตัดชื่อ layer ทิ้งเมื่อ export ทำให้พึ่งชื่ออย่างเดียวไม่ได้ ลำดับการหาคือ

1. **หา boxes จาก shape** — tensor ตัวเดียวที่มิติสุดท้าย = 4 (`[1, N, 4]`) → เจอแน่นอน
2. **หา num_detections** — tensor ที่มี 1 element และชื่อมีคำว่า `num` (ถ้าไม่มีชื่อ ก็ใช้ตัวที่มี 1 element)
3. **ที่เหลือ 2 ตัวคือ classes กับ scores** — ถ้าชื่อมีคำว่า `class`/`score` ก็ใช้ชื่อ
   ถ้าไม่มีชื่อ ก็ใช้ลำดับมาตรฐาน (`locations, classes, scores, num`) แล้ว **ตรวจซ้ำด้วยข้อมูลจริงในเฟรมแรก**:
   class index เป็นจำนวนเต็ม แต่ score ไม่ใช่ → ถ้าสลับกัน โค้ดจะสลับกลับให้พร้อม log เตือน

### ค่า input scaling

ตรวจจาก dtype และ quantization parameters ของ input tensor (override ได้ด้วย `Config.inputScaling`):

| เงื่อนไข | Domain ที่ป้อน | ตัวอย่างโมเดล |
|----------|----------------|----------------|
| `float32` | `channel / 255` (0..1) | EfficientDet-Lite จาก Model Maker, `ssd_mobilenet_v1.tflite` (float) |
| quantized, zero point ≤ -64 หรือ scale ≈ 1/255 | `channel / 255` (0..1) | โมเดล INT8/UINT8 ที่ทำ full-integer quantization แบบ 0..1 |
| quantized, scale ≈ 1/127.5 | `channel / 127.5 - 1` (-1..1) | โมเดลที่ใช้ mean=127.5 std=127.5 |
| อื่น ๆ | `channel` (0..255) | โมเดลที่ normalize ในกราฟเอง |

ถ้า scaling ผิด อาการคือ **ตรวจไม่เจออะไรเลย แต่ก็ไม่ error** — เป็นบั๊กที่หายาก
จึงควรเปิดแอปแล้วดูว่ามีกรอบขึ้นบ้างไหมก่อนไปเทรนเพิ่ม

## 2. `labels.txt`

ไฟล์ `app/src/main/assets/labels.txt` บอกว่า index ไหนคือคลาสอะไร (บรรทัดที่ขึ้นต้นด้วย `#` = คอมเมนต์):

```
# index 0
red_light
# index 1
yellow_light
# index 2
green_light
```

- **ลำดับบรรทัด = class index ของโมเดล** ต้องตรงกับ label map ที่ใช้ตอนเทรน
- ชื่อต้องเป็น `red_light` / `yellow_light` / `green_light` (ตัวพิมพ์เล็ก/ใหญ่, `-`, ช่องว่าง ไม่สำคัญ
  เพราะโค้ด normalize ให้) — คลาสอื่นจะถูกมองข้าม (background/ป้ายอื่น)
- เพราะโค้ดผูกด้วย **ชื่อ label** ไม่ใช่ index ตายตัว ถ้าโมเดลของคุณเรียง index ต่างออกไปก็ยังทำงานถูก

## 3. ชุดข้อมูล

ใช้รูปแบบ **Pascal VOC** (`.xml` ต่อภาพ 1 ไฟล์):

```
dataset/
├── train/
│   ├── images/           001.jpg, 002.jpg, ...
│   └── annotations/      001.xml, 002.xml, ...
└── validation/
    ├── images/
    └── annotations/
```

ตัวอย่าง `001.xml`:

```xml
<annotation>
  <folder>train</folder>
  <filename>001.jpg</filename>
  <size>
    <width>1024</width>
    <height>768</height>
    <depth>3</depth>
  </size>
  <object>
    <name>green_light</name>
    <pose>Unspecified</pose>
    <truncated>0</truncated>
    <difficult>0</difficult>
    <bndbox>
      <xmin>612</xmin>
      <ymin>248</ymin>
      <xmax>648</xmax>
      <ymax>292</ymax>
    </bndbox>
  </object>
  <object>
    <name>red_light</name>
    <bndbox>
      <xmin>120</xmin>
      <ymin>252</ymin>
      <xmax>158</xmax>
      <ymax>296</ymax>
    </bndbox>
  </object>
</annotation>
```

> `export_tflite.py` ใช้ `label_map = {1: "red_light", 2: "yellow_light", 3: "green_light"}`
> — ถ้าไฟล์ XML ของคุณใช้ id ต่างจากนี้ ให้แก้ `VOC_LABEL_MAP` ในสคริปต์ให้ตรง

### เคล็ดลับการ label

- **ตีกรอบที่ "ดวงไฟ" ที่ติดสว่าง** ไม่ใช่ที่ตัวเรือนทั้งหมด — คลาสคือ *สีของไฟ* จึงต้องให้โมเดลเห็นสี
  ของดวงที่ติด ถ้าตีทั้งตัวเรือน โมเดลจะได้ทั้งไฟ 3 ดวงในกรอบเดียวและแยกสีได้แย่ลง
- ถ้าต้องการรู้ "มีไฟจราจรอยู่ตรงไหน" มากกว่าสี ให้เพิ่มคลาส `traffic_light` แยกแล้วค่อยตัดสินสีด้วย
  ภาพบริเวณนั้น (งานคนละแบบ ต้องแก้ `TrafficLightClass` ด้วย)
- เก็บภาพให้หลากหลาย: **กลางวัน/กลางคืน**, ฝน, ไฟท้ายสะท้อน, มุมเฉียง, ไฟถูกลดความสว่าง,
  ระยะไกล/ใกล้, กล้องสั่น และถ่ายตอนรถติด
- ไฟจราจรเป็นวัตถุ **เล็ก** ในภาพ — ควรมีภาพที่ไฟกินพื้นที่อย่างน้อย ~1-3% ของภาพ
  และถ้าถ่ายจากในรถ ให้ใส่ภาพสะท้อนบนกระจกเป็นตัวอย่าง "ไม่มี object" ด้วย
- สมดุลคลาส: ถ้าเก็บจากถนนจริง ไฟเขียวจะเยอะกว่าแดง/เหลืองมาก ให้เก็บเพิ่มหรือใช้ augmentation
  ไม่งั้นโมเดลจะ bias ไปที่เขียว ซึ่งจะทำให้ **เตือนผิดบ่อย** (ผลกระทบร้ายแรงที่สุดของแอปนี้)

## 4. เทรนและ export

### ทางที่ 1 — TFLite Model Maker (สคริปต์ในโปรเจกต์)

```bash
pip install -r tools/requirements.txt      # ต้องใช้ Python 3.9–3.11, TensorFlow <= 2.15
python tools/export_tflite.py \
  --dataset dataset \
  --model efficientdet-lite0 \
  --epochs 50 \
  --output app/src/main/assets
```

สคริปต์จะเทรน EfficientDet-Lite0 (3 คลาส), เขียน `traffic_light.tflite` และ `labels.txt`
ลงใน `app/src/main/assets/` ให้เอง แล้วแจ้งเตือนให้ตรวจสอบด้วย `tools/inspect_model.py`

เลือกขนาดโมเดลที่เหมาะกับงาน:

| โมเดล | ขนาดโดยประมาณ | ใช้เมื่อ |
|-------|----------------|----------|
| `efficientdet-lite0` | เล็กสุด, เร็วสุด | ต้องการ > 15 fps บนมือถือราคากลาง (แนะนำ) |
| `efficientdet-lite1` | กลาง | ยอมช้าลงเพื่อความแม่นกับไฟระยะไกล |
| `efficientdet-lite2` | ใหญ่สุด | เครื่องเรือธง + ไฟที่เล็กมาก |

### ทางที่ 2 — TensorFlow Model Garden / Keras + TFLite Converter

ถ้า Python ในเครื่องใหม่กว่า 3.11 (Model Maker เลิกดูแลแล้ว) ให้เทรนด้วย TF Model Garden
หรือเขียน Keras model เอง แล้วใช้:

```python
import tensorflow as tf
converter = tf.lite.TFLiteConverter.from_saved_model("exported/saved_model")
converter.optimizations = [tf.lite.Optimize.DEFAULT]          # INT8
converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
converter.representative_dataset = representative_dataset      # จำเป็นสำหรับ full-integer quantization
converter.inference_input_type = tf.uint8
converter.inference_output_type = tf.uint8
tflite_model = converter.convert()
open("app/src/main/assets/traffic_light.tflite", "wb").write(tflite_model)
```

### ทางที่ 3 — YOLO (Ultralytics)

`yolo export format=tflite int8=True` จะได้ TFLite ที่มี NMS อยู่ในกราฟ (output เป็น
`[1, N, 4]` boxes, `[1, N]` scores, `[1, N]` classes) ซึ่ง **ใช้กับแอปนี้ได้เลย** โดยแค่ตรวจ
ด้วย `inspect_model.py` ก่อน ถ้าโมเดลที่ได้ยัง output เป็น anchor ดิบ (`[1, 8400, 85]`)
แอปนี้จะใช้งานไม่ได้ (ต้องเพิ่มขั้นตอน decode เอง)

## 5. Quantization

| แบบ | ขนาด | ความเร็ว | หมายเหตุ |
|-----|------|---------|----------|
| float32 | 100% | ช้า (แต่ใช้ GPU delegate ได้) | แม่นสุด, ไฟล์ใหญ่สุด |
| **INT8 (full integer)** | ~25% | เร็วสุดบน CPU/NNAPI | แนะนำสำหรับแอปจริง — ต้องมี representative dataset |

แอปนี้รองรับทั้งสองแบบโดยไม่ต้องแก้โค้ด — `TrafficLightDetector` อ่าน dtype และ
quantization parameters ของทุก tensor แล้ว dequantize ให้อัตโนมัติ (ทั้ง `UINT8` และ `INT8`
รวมถึงการอ่านค่าติดลบของ `INT8` อย่างถูกต้อง)

## 6. ตรวจสอบโมเดลก่อนใช้งาน

```bash
python tools/inspect_model.py app/src/main/assets/traffic_light.tflite
```

ตัวอย่างผลลัพธ์ของโมเดลที่ใช้ได้ (float32):

```
Model: app/src/main/assets/traffic_light.tflite (4.41 MB)

Inputs:
  serving_default_input:0  shape=[1, 320, 320, 3]  dtype=float32

Outputs:
  StatefulPartitionedCall:0  shape=[1, 25, 4]  dtype=float32
  StatefulPartitionedCall:1  shape=[1, 25]  dtype=float32
  StatefulPartitionedCall:2  shape=[1, 25]  dtype=float32
  StatefulPartitionedCall:3  shape=[1]  dtype=float32

Compatible: the app can load this model.
Remember that labels.txt must list the labels in the model's class index order.
```

ตัวอย่างโมเดล INT8:

```
Inputs:
  serving_default_input:0  shape=[1, 320, 320, 3]  dtype=uint8  quant: scale=[0.003921568859368563] zero_point=[0]

Outputs:
  StatefulPartitionedCall:0  shape=[1, 25, 4]  dtype=uint8  quant: scale=[0.0234...] zero_point=[12]
  StatefulPartitionedCall:1  shape=[1, 25]  dtype=uint8  quant: scale=[1.0] zero_point=[0]
  StatefulPartitionedCall:2  shape=[1, 25]  dtype=uint8  quant: scale=[0.0039...] zero_point=[0]
  StatefulPartitionedCall:3  shape=[1]  dtype=uint8  quant: scale=[1.0] zero_point=[0]
```

ถ้าเจอ `INCOMPATIBLE` ให้แก้ที่ตัว export (ต้องมี output `[1, N, 4]`) ไม่ใช่ที่ตัวแอป

## 7. ยังไม่มีโมเดล? ทดสอบ pipeline ก่อนได้

```bash
python tools/make_dummy_model.py            # เขียน app/src/main/assets/traffic_light.tflite
```

ได้โมเดลจิ๋ว (ไม่ต้องเทรน) ที่ output ตรงสัญญาทั้ง 4 tensor แต่ค่าที่ทำนายเป็น "ขยะ"
ประโยชน์คือทดสอบได้ครบว่า: interleaved output ถูกอ่านถูก index, overlay วาดกรอบได้,
NMS ไม่ crash, gate ทำงาน และสวิตช์/สไลเดอร์ทำงาน — ก่อนเสียเวลาเทรนโมเดลจริง

> อย่าลืมลบ/เขียนทับไฟล์นี้ด้วยโมเดลจริงก่อนใช้งาน และ **อย่า commit โมเดลปลอม**
> ทับของจริง (ดู `.gitattributes` — ไฟล์ `.tflite` ถูกทำเครื่องหมายเป็น binary)

## 8. การติดตั้งกล้องบนรถ (มีผลต่อความแม่นมาก)

- วางมือถือให้ **นิ่ง** (ขายึดติดกระจก) — ภาพสั่นทำให้กรอบแกว่งและ debounce อาจรีเซ็ตบ่อย
- อย่าให้ **แสงสะท้อน** บนกระจกบังไฟ — ฝนตก/กลางคืนคือสถานการณ์ที่แย่สุด
- ให้ไฟจราจรอยู่ในกลางภาพตอนขับปกติ — โมเดลถูกเทรนจากภาพมุมนี้เป็นหลัก
- ถ้าต้องใช้กลางคืนบ่อย ให้เก็บภาพกลางคืนเข้า dataset เยอะ ๆ หรือเพิ่มการปรับความสว่าง (gamma)
  ก่อนส่งเข้าโมเดล (ยังไม่ทำในโปรเจกต์นี้ — ดู [ROADMAP.md](ROADMAP.md))

## 9. ชุดข้อมูลสาธารณะ

มี dataset ไฟจราจรที่ถูกอ้างอิงกันบ่อย (เช่น LISA Traffic Light Dataset, Bosch Small Traffic
Lights) — **ตรวจสอบเงื่อนไขการใช้งาน/สัญญาอนุญาตก่อนนำมาใช้** และอาจต้องแปลง annotation
เป็นรูปแบบ Pascal VOC เอง ก่อนนำเข้า `tools/export_tflite.py`
