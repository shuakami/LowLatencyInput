# LowLatencyInput - 移动输入映射到PC的解决方案

[![Status](https://img.shields.io/badge/status-on%20hold-orange)](https://shields.io/)
[![License](https://img.shields.io/badge/License-CC%20BY--NC--SA%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc-sa/4.0/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.x-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![C++](https://img.shields.io/badge/C++-17-blue.svg?logo=c%2B%2B)](https://isocpp.org/)
[![Platform](https://img.shields.io/badge/Platform-Android-brightgreen.svg?logo=android)](https://developer.android.com)

**注意：本项目目前处于搁置状态，正在寻找新的维护者或贡献者。核心功能已部分实现，但存在已知问题和未完成的部分。**

## 概述

`LowLatencyInput` 是一个实验性的解决方案，旨在将移动设备的输入（触摸、陀螺仪、加速度计）以低延迟的方式映射并转发到 PC 端，用于模拟键鼠或手柄操作，尤其针对 PC 游戏（如 The Finals）场景。

## 动机

许多玩家习惯于移动端的操作方式（如触摸瞄准、体感辅助），但在 PC 端使用键鼠时感到不适或效率低下。本项目尝试通过利用 Android 底层能力和网络通信，将移动设备与 PC 结合，提供一种高度可定制的低延迟输入方案，以弥补这一差距。

## 主要特性 (部分实现)

*   **低延迟输入捕获:** 通过 C++ NDK 直接读取 Android 输入设备事件 (`/dev/input/eventX`)，绕过标准事件分发，以降低延迟。
*   **传感器数据采集:** 以较高频率 (50Hz) 采集陀螺仪和加速度计数据。
*   **动态悬浮窗 UI:** 支持通过配置文件加载和显示自定义的悬浮窗布局（按钮、图标等）。
*   **触摸区域感知:** C++ 层能够识别触摸事件是否发生在悬浮窗 UI 元素定义的区域内。
*   **事件区分:** C++ 层能够区分单击、长按开始 (>150ms) 和长按结束事件，并生成不同类型的通知。
*   **TCP 网络转发:** 将处理后的 UI 事件、原始触摸数据流和传感器数据通过 TCP 协议发送到目标服务器（默认为 `127.0.0.1:12345`）。
*   **自定义协议:** 定义了简单的二进制协议来区分不同类型的数据包（触摸、陀螺仪、加速度计、UI 事件等）。

## 架构概览

本解决方案包含 **Android 客户端**、**服务器端** 和 **PC 输入驱动/模拟层** 三个主要部分，它们将协同工作并完整开源。

*   **Android 客户端 (本项目):**
    *   **UI 层 (Kotlin):** `MainActivity` 提供用户界面和控制入口，`OverlayEditorActivity` (推测) 用于编辑悬浮窗布局，`RuntimeOverlayService` 动态加载并显示悬浮窗元素。
    *   **服务层 (Kotlin):** `GyroscopeService` 作为核心枢纽，管理 C++ 层、采集传感器、处理 JNI 回调、并通过 `TcpCommunicator` 进行网络通信。`ServiceManager` 协调服务生命周期。
    *   **Native 层 (C++):** 使用 NDK 和 CMake 构建。`InputReader` 负责直接读取输入事件、进行坐标转换、区域命中和长按检测。`JNI Bridge` 处理 Kotlin 与 C++ 之间的双向通信。`Permissions` 部分尝试使用 Root 权限修复设备访问问题。
*   **服务器端:** 运行在 PC 或中继设备上，接收来自 Android 客户端的 TCP 数据，进行解析和处理 (使用 Rust 实现)。
*   **PC 输入驱动/模拟层:** 运行在 PC 上，接收来自服务器端处理后的指令，模拟生成键鼠、手柄输入事件 (使用 C++ 实现)。

**完整解决方案组件:**

下表列出了构成完整解决方案的各个开源组件：

| 组件名称                 | 描述                                                                                                  | 仓库地址                                                              |
| :----------------------- | :---------------------------------------------------------------------------------------------------- | :---------------------------------------------------------------- |
| `LowLatencyInput`        | **(本项目)** Android 客户端，负责捕获触摸/传感器数据并通过 TCP 发送到 PC。                             | [shuakami/LowLatencyInput](https://github.com/shuakami/LowLatencyInput) |
| `android_usb_listener` | (推测) PC 端服务，接收来自 Android 的 TCP 数据，解析并写入共享内存。                                   | [shuakami/android_usb_listener](https://github.com/shuakami/android_usb_listener) |
| `Fastkey`                | PC 端 Windows 客户端，读取共享内存数据（触摸、UI 事件），低延迟模拟鼠标/键盘操作。                         | [shuakami/Fastkey](https://github.com/shuakami/Fastkey)               |
| `gyroxinput`             | PC 端 Windows 客户端，读取共享内存数据（陀螺仪、加速度计），使用 ViGEmBus 模拟虚拟 DS4 手柄的运动控制。 | [shuakami/gyroxinput](https://github.com/shuakami/gyroxinput)           |

## ⚠️ Root 权限要求与风险 ⚠️

**本项目严重依赖 Root 权限才能正常运行！**

Root 权限主要用于：

1.  **尝试自动授予悬浮窗权限:** 在 `MainActivity` 中通过 `appops set ... SYSTEM_ALERT_WINDOW allow` 命令。
2.  **访问输入设备文件:** 在 C++ 层，如果无法读取 `/dev/input/eventX`，会尝试通过 `su -c setenforce 0` **全局禁用 SELinux 强制模式**，并通过 `su -c chmod 666 <device>` 修改设备文件权限。

**这些操作具有非常高的安全风险！** 请在完全理解后果的情况下使用本项目。不当使用可能导致系统不稳定或安全漏洞。**强烈建议仅在测试设备上运行。**

## 当前状态与已知问题

*   **状态:** 核心的输入读取、区域命中、事件回调、网络转发、服务器处理和 PC 端模拟框架均已建立。
*   **未完成:**
    *   **虚拟摇杆:** 核心功能缺失，需要实现触摸点在摇杆区域内滑动时的数据生成逻辑。
*   **已知问题:**
    *   **延迟:** 尽管努力优化，在实际游戏（如 The Finals）中仍可能存在可感知的延迟。
    *   **后台传感器数据:** 应用退至后台一段时间后，陀螺仪等传感器数据可能停止发送。

## 技术栈

*   **语言:** Kotlin, C++ (NDK)
*   **构建:** Gradle (with Kotlin DSL), CMake
*   **核心库/技术:**
    *   Android SDK & NDK
    *   Libsu
    *   Kotlin Coroutines & Flow (用于异步和状态管理)
    *   Android Services (Foreground Service)
    *   WindowManager (用于悬浮窗)
    *   JNI (Java Native Interface)
    *   Linux Input Subsystem
    *   TCP Sockets
    *   nlohmann/json (C++ JSON 库)
    *   Gson (Kotlin JSON 库)

## 网络协议概览 (客户端 -> 服务器)

客户端与服务器通过 TCP 连接 (`127.0.0.1:12345` 默认) 进行通信。所有发送的数据包都包含一个包头和一个可选的 Payload。

**包头结构:**

存在两种主要的包头结构，取决于包类型：

1.  **标准包头 (9 字节):** 用于触摸流、传感器数据、设备信息和 PING 请求。
    *   `Packet Type` (1 Byte): 包类型标识 (见下文)。
    *   `Timestamp` (8 Bytes, **BigEndian**): 发送时的纳秒时间戳 (`System.nanoTime()`)。

2.  **UI 事件包头 (11 字节):** 用于 UI 点击、长按结束和长按开始事件。
    *   `Packet Type` (1 Byte): 包类型标识 (见下文)。
    *   `Timestamp` (8 Bytes, **BigEndian**): 发送时的纳秒时间戳 (`System.nanoTime()`)。
    *   `Payload Length` (2 Bytes, **LittleEndian**): 后面跟随的 Payload 的字节长度。

**主要包类型与 Payload 结构 (`Constants.kt`):**

*   **`0x01`: 触摸事件 (Touch)**
    *   包头: 标准包头 (9 字节)
    *   Payload: 变长，由 `GyroscopeService.onInputDataReceivedFromNative` 构建。
        *   `Event Timestamp` (8 Bytes, **LittleEndian**): C++ 事件发生时的时间戳 (ms)。
        *   `Touch Count` (1 Byte): 当前包包含的触摸点数量 (N)。
        *   `Touches` (N * 12 Bytes): 每个触摸点数据：
            *   `ID` (4 Bytes, **LittleEndian**)
            *   `X` (4 Bytes, **LittleEndian**): 屏幕 X 坐标 (px)。
            *   `Y` (4 Bytes, **LittleEndian**): 屏幕 Y 坐标 (px)。

*   **`0x02`: 陀螺仪数据 (Gyro)**
    *   包头: 标准包头 (9 字节)
    *   Payload (20 Bytes):
        *   `Reserved` (8 Bytes): 当前未使用，填充 0。
        *   `X` (4 Bytes, **BigEndian**): 陀螺仪 X 轴数据 (float)。
        *   `Y` (4 Bytes, **BigEndian**): 陀螺仪 Y 轴数据 (float)。
        *   `Z` (4 Bytes, **BigEndian**): 陀螺仪 Z 轴数据 (float)。

*   **`0x04`: 加速度计数据 (Accel)**
    *   包头: 标准包头 (9 字节)
    *   Payload (20 Bytes): 结构同陀螺仪数据。

*   **`0x05`: UI 点击事件 (UI Event)**
    *   包头: UI 事件包头 (11 字节)
    *   Payload (变长):
        *   `Click X` (4 Bytes, **LittleEndian**): 点击位置 X 坐标 (px)。
        *   `Click Y` (4 Bytes, **LittleEndian**): 点击位置 Y 坐标 (px)。
        *   `Identifier` (N Bytes, UTF-8): 触发事件的 UI 元素名称。

*   **`0x06`: 设备信息 (Device Info)**
    *   包头: 标准包头 (9 字节)
    *   Payload (8 Bytes):
        *   `Screen Width` (4 Bytes, **LittleEndian**): 屏幕宽度 (px)。
        *   `Screen Height` (4 Bytes, **LittleEndian**): 屏幕高度 (px)。

*   **`0x07`: UI 长按结束事件 (UI Long Press)**
    *   包头: UI 事件包头 (11 字节)
    *   Payload (变长): 结构同 UI 点击事件。

*   **`0x08`: UI 按下/长按开始事件 (UI Press Down)**
    *   包头: UI 事件包头 (11 字节)
    *   Payload (变长):
        *   `Click X` (4 Bytes, **LittleEndian**): 按下位置 X 坐标 (px)。
        *   `Click Y` (4 Bytes, **LittleEndian**): 按下位置 Y 坐标 (px)。
        *   `Down Timestamp` (8 Bytes, **LittleEndian**): C++ 检测到的按下时间戳 (ms)。
        *   `Identifier` (N Bytes, UTF-8): 触发事件的 UI 元素名称。

*   **`0x03`: PING 请求**
    *   包头: 标准包头 (9 字节)
    *   Payload: 空 (0 字节)

**服务器 -> 客户端:**

*   **`0xFE`: PING 响应 (ACK)**
    *   客户端预期接收的包结构 (基于 `startServerListener`): `类型(1B, 0xFE) + 时间戳(8B, BigEndian) + 长度(2B, LittleEndian, 值为0)`。客户端使用时间戳计算 RTT。

## 设置与构建 (Android 客户端)

1.  **环境要求:**
    *   Android Studio (最新稳定版推荐)
    *   Android NDK (通过 Android Studio SDK Manager 安装)
    *   CMake (通过 Android Studio SDK Manager 安装)
    *   已 Root 的 Android 设备 (用于运行和测试)
2.  **克隆仓库:**
    ```bash
    git clone https://github.com/shuakami/LowLatencyInput.git
    cd LowLatencyInput
    ```
3.  **打开项目:** 使用 Android Studio 打开项目根目录。
4.  **构建:** Gradle 会自动同步并处理依赖。点击 Build -> Make Project 或直接运行 `app` 模块。
5.  **运行:**
    *   确保你的 Android 设备已通过 USB 连接并启用了开发者选项和 USB 调试。
    *   在 Android Studio 中选择你的设备并运行 `app`。
    *   首次运行时，应用可能会请求 Root 权限（用于悬浮窗或输入设备访问）。
    *   **注意:** 默认配置下，应用会尝试连接到 `127.0.0.1:12345`。你需要设置 ADB 端口转发 (`adb reverse tcp:12345 tcp:12345`) 并确保 PC 上有对应的服务器在监听该端口才能成功连接。

## 如何贡献

欢迎对本项目感兴趣的开发者进行贡献！我们尤其需要：

*   **实现虚拟摇杆功能:** 这是目前最核心的缺失功能。
*   **修复已知问题:** 特别是后台传感器数据丢失、设备兼容性问题。
*   **性能优化:** 进一步降低端到端延迟。
*   **提高安全性:** 探索替代 Root 权限或更安全的权限获取方式。
*   **完善文档:** 补充更详细的架构说明、协议文档等。
*   **代码重构与改进:** 提高代码质量和稳定性。

**贡献流程:**

1.  Fork 本仓库。
2.  创建新的 Feature 分支 (`git checkout -b feature/AmazingFeature`)。
3.  进行代码修改。
4.  Commit 你的修改 (`git commit -m 'Add some AmazingFeature'`)。
5.  Push 到你的 Feature 分支 (`git push origin feature/AmazingFeature`)。
6.  创建 Pull Request。

