# 第三方致谢与声明 (Third-party notices)

本项目（MuseRecorder）的 Muse BLE 协议实现参考并部分移植自以下 MIT 许可的开源项目：

## muse-lsl
- 来源：https://github.com/alexandrebarachant/muse-lsl
- 许可：MIT（Copyright (c) Alexandre Barachant 及贡献者）
- 使用部分：GATT 属性表、legacy 数据包解码、控制命令帧、RLS 时间戳去抖算法、Athena 连接初始化序列。

## BrainFlow
- 来源：https://github.com/brainflow-dev/brainflow（Athena 解码见 PR #779）
- 许可：MIT（Copyright BrainFlow devs 及贡献者）
- 使用部分：Muse S (Gen 2 / Athena) 多路复用帧拆分与 14-bit EEG / int16 IMU / 电池子包解码逻辑（经 muse-lsl 移植链）。

上述项目的许可见各自仓库；按 MIT 条款在此保留版权与许可声明。

## 商标声明

"Muse" 是 InteraXon Inc. 的商标。本项目为独立社区作品，
**与 InteraXon Inc. 无隶属、合作或认可关系**，仅实现与其硬件的开放 BLE 通信。
