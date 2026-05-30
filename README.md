# 提词器 (PromptPlayer)

> 一款基于语音识别的 Android 提词器应用，支持离线语音控制文本自动滚动。

## 功能特点

- **📄 文档导入** — 支持导入 `.txt` 和 `.docx` 格式文档
- **🎙️ 离线语音识别** — 集成 [Vosk](https://alphacephei.com/vosk/) 离线语音引擎，无需联网
- **🤖 智能语音跟读** — 语音识别到已读内容时，自动滚动到对应位置
- **🎨 沉浸深色界面** — Material 3 + 深色渐变主题，专注阅读体验
- **⚡ 流畅动画** — 句子切换时有平滑的过渡动画
- **🔧 字体大小调节** — 支持在设置中调整文字大小

## 技术栈

| 技术 | 用途 |
|------|------|
| **Kotlin** | 开发语言 |
| **Jetpack Compose** | UI 框架 |
| **Material 3** | 设计系统 |
| **MVVM** | 架构模式 |
| **Vosk Android** | 离线语音识别 |
| **Apache POI** | DOCX 文档解析 |
| **Coroutines** | 异步处理 |

## 截图

| 主界面 | 跟读滚动 |
|-------|---------|
| <img src="screen.png" width="200"> | <img src="emulator_screen.png" width="200"> |

## 快速开始

### 前置条件

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34

### 构建运行

1. 克隆项目

   ```bash
   git clone https://github.com/w2278222-web/-prompter.git
   ```

2. 下载中文语音模型

   从 [Vosk 模型页面](https://alphacephei.com/vosk/models) 下载中文模型 `vosk-model-small-cn-0.22`，解压后将文件夹放入 `app/src/main/assets/vosk-model-cn/` 目录。

3. 在 Android Studio 中打开项目，同步 Gradle 后运行。

### 项目结构

```
提词器/
├── app/
│   └── src/main/
│       ├── java/com/promptplayer/
│       │   ├── data/
│       │   │   └── DocumentParser.kt          # 文档解析器
│       │   ├── domain/
│       │   │   └── TextMatcher.kt              # 文本匹配算法
│       │   ├── presentation/
│       │   │   ├── MainActivity.kt             # 主界面 & UI
│       │   │   └── PromptViewModel.kt          # ViewModel
│       │   └── speech/
│       │       ├── SpeechRecognitionHelper.kt   # 语音识别抽象
│       │       └── VoskSpeechRecognitionHelper.kt  # Vosk 实现
│       └── res/                                # 资源文件
├── build.gradle.kts                            # 项目级构建配置
├── settings.gradle.kts                         # 项目设置
└── gradle.properties                           # Gradle 属性
```

## 使用方法

1. **导入文档** — 点击右下角 **+** 按钮，选择 TXT 或 DOCX 文件
2. **开始跟读** — 点击底部麦克风按钮开始语音识别
3. **自动滚动** — 说出文档中的内容，应用会自动跳转到对应位置
4. **调整设置** — 点击齿轮图标可调节字体大小

## 语音匹配算法

应用使用 **前缀匹配 + Jaccard 相似度** 双重算法进行语音定位：

- **前缀匹配** — 优先在当前句子附近查找以识别文本开头的句子
- **Jaccard 相似度** — 计算识别文本与候选句子的词袋相似度
- **位置衰减** — 距离当前位置越远的句子权重越低，避免误跳转
- **向后惩罚** — 向后跳转有额外惩罚，防止反复跳回已读内容

## 权限

- **麦克风权限** — 用于语音识别
- **存储权限** — 用于读取文档文件（Android 13+ 使用 SAF）

## 依赖

- AndroidX Core KTX 1.12+
- Jetpack Compose BOM 2023.10+
- Material 3 1.1.2
- Vosk Android 0.3.47
- Apache POI 5.2.4

完整依赖请查看 [app/build.gradle.kts](app/build.gradle.kts)。

## License

```
Copyright 2026 PromptPlayer

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
