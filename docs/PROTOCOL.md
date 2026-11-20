# Muse BLE 协议参考（本项目实现依据）

来源：muse-lsl（`muselsl/muse.py`、`constants.py`、`athena.py`，athena 解码移植自 BrainFlow PR #779）、
Mind Monitor 官方 CSV/OSC 规范（mind-monitor.com/FAQ.php）。本文档为逆向工程社区成果的整理。

## 1. GATT 属性

服务广播：`0000fe8d-0000-1000-8000-00805f9b34fb`（Interaxon）。数据特征 base UUID：
`273eXXXX-4c4d-454d-96be-f03bac821358`。

| 特征 | UUID 尾段 | 协议 | 说明 |
|---|---|---|---|
| CONTROL | 273e0001 | 两者 | 写命令 / notify 回复 |
| EEG_TP9 | 273e0003 | legacy | 每通道 EEG 流 |
| EEG_AF7 | 273e0004 | legacy | （AF7 包到达即一组完成） |
| EEG_AF8 | 273e0005 | legacy | |
| EEG_TP10 | 273e0006 | legacy | |
| EEG_RIGHT_AUX | 273e0007 | legacy | |
| GYRO | 273e0009 | legacy | |
| ACCELEROMETER | 273e000a | legacy | |
| TELEMETRY | 273e000b | legacy | 电池/电压/温度 |
| PPG1/2/3 | 273e000f/10/11 | legacy | ambient / infrared / red |
| THERMISTOR | 273e0012 | legacy(Muse S) | |
| **ATHENA_DATA1** | **273e0013** | Athena | 多路复用数据流 |
| ATHENA_DATA2 | 273e0014 | Athena | 多路复用数据流 |

协议探测：连接后发现 `273e0013` → Athena；否则有 `273e0003` → legacy。

## 2. 控制通道

命令帧：`[len+1, ASCII..., '\n']`，写入 CONTROL，**write-without-response**。
回复：`[n][ASCII×n]` 分段（20 字节 notify），JSON 以 `}` 结束（`hn` 名称、`sn` 序列号、`bp` 电量%、
`fw` 固件、`hw` 硬件、`ps` 预置、`rc` 状态码）。

| 命令 | 含义 |
|---|---|
| `v1` / `v6` | 查询设备信息（legacy / Athena） |
| `s` | legacy：状态查询；Athena：开始/保持数据流 |
| `d` | legacy：开始/恢复数据流 |
| `h` | 停止数据流 |
| `k` | 保活（legacy） |
| `L0` / `L1` | 灯灭 / 灯亮（Athena 中 L1 亦用作低延迟标记） |
| `dc001` | Athena 开始下行数据（连发两次） |
| `pXX` | 预置。legacy 默认 `p21`；Athena 默认 `p1041`（有效集含 p1034..p1046, p4129, p20/21/50/51/60/61） |
| `*1` | 复位 |

### 连接初始化序列（本项目）

- **Athena**：订阅 CONTROL+DATA1+DATA2 → `v6,s,h,p1041`（各隔 200ms）→ `dc001×2`（60ms）→ `L1` → `s`
- **Legacy**：订阅 EEG×5+CONTROL+TELEMETRY(+ACC/GYRO/PPG) → `p21` → `v1` → `s` → `d`

## 3. Athena 帧格式（Muse S Gen 2）

一次 notify 含若干长度前缀帧：

```
[len][idx_lo][idx_hi][6 字节保留][tag@9][block_idx@10][主块数据...]  ← 14 字节帧头
其后可跟子包：[tag][sub_idx][数据...]                                ← 5 字节子包头
package_num = (packet_index << 8) | block/sub index
```

传感器标签：

| tag | 类型 | 通道×样本 | 率 | 数据长度 | 编码 |
|---|---|---|---|---|---|
| 0x11 | EEG | 4×4 | 256 Hz | 28 | 14-bit LSB-first，样本主序，×(1450/16383)−8192 µV |
| 0x12 | EEG | 8×2（前 4 通道为主 EEG） | 256 Hz | 28 | 同上 |
| 0x34 | 光学 | 4×3 → 规范通道 4..7 | 64 Hz | 30 | 20-bit LSB-first |
| 0x35 | 光学 | 8×2 → 0..7 | 64 Hz | 40 | 同上 |
| 0x36 | 光学 | 16×1 → 0..15 | 64 Hz | 40 | 同上 |
| 0x47 | ACC+GYRO | 6×3（前 3 轴 ACC，后 3 轴 GYRO） | 52 Hz | 36 | int16 **LE**；ACC ×0.0000610352 g；GYRO ×**−0.0074768** dps |
| 0x53 | 未知 | 2×6 | 32 Hz | 24 | 忽略 |
| 0x88 | 电池 | 变长 | — | — | 首 uint16 LE ×(1/512) = % |
| 0x98 | 电池 | 1×1 | ~1 Hz | 20 | 同上 |

注：Athena 帧可 >20 字节，中心须主动 `requestMtu(185)`（Android 默认 MTU 23 时无法收全帧）。

## 4. Legacy 包格式（每通道特征，均为大端位打包）

- **EEG**（20 字节）：`uint16 序号 + 12 × uint12`；µV = (raw−2048)×0.48828125（2 mVpp）。
  5 通道以 AF7 包到达为一组；序号跳变时样本序号前跳 `12×(Δ+1)`（−65535 为计数器回绕）。
- **ACC/GYRO**（20 字节）：`uint16 序号 + 9 × int16`，样本主序 `[x0 y0 z0 x1 …]`；
  ACC ×0.0000610352 g；GYRO ×0.0074768 dps；52 Hz（每包 3 样本）。
- **PPG**（20 字节）：`uint16 序号 + 6 × uint24`，三特征（ambient/IR/red）以 red 包到齐为一组；64 Hz。
- **遥测**：5 × uint16：`[序号, 电池raw(÷512=%), 燃料计raw(×2.2=mV), ADC, 温度(×0.01 ℃)]`。

## 5. 时间戳

所有时间戳以宿主到达时间为锚，RLS（递归最小二乘，参考 arXiv:1308.3846）在线拟合
`样本序号 → 宿主时间`（斜率≈1/率），输出去抖后的每样本时间。每条流（EEG/ACC/GYRO/PPG/光学）
各持一个校正器。

## 6. Mind Monitor CSV 规范（对照）

`TimeStamp(yyyy-MM-dd HH:mm:ss.mmm) | RAW_{TP9,AF7,AF8,TP10}(µV) | AUX | Accelerometer_{X,Y,Z}(g) |
Gyro_{X,Y,Z}(dps) | PPG_* | Optics_*(µA) | HeadBandOn | HSI_{TP9..TP10}(1/2/4) | Battery | Elements(标注)`。
