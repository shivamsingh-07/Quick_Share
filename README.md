# Quick Share for Google TV

A lightweight app that leverages Google Quick Share to seamlessly transfer files between Android phones and TV's. No cables, no cloud — just fast, local wireless sharing across your devices.

<div align="center">
    <a href="https://github.com/shivamsingh-07/Quick_Share/releases/tag/Latest">
        <img src="https://img.shields.io/badge/Download-Latest_Release-green?style=for-the-badge&logo=github" alt="Download from GitHub">
    </a>
</div>

## ✨ Features

- 🔐 End-to-End Encryption
- 📁 Seamless File Transfer
- 📺 Optimized for Google TV
- 🔳 QR Code Sharing

## ⚙️ Setup & Installation

### Requirements

- JDK 17
- Android SDK (API 35)
- Android TV 11 and newer devices
- ADB

### Build and Run

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install on connected device/emulator
./gradlew :app:installDebug

# Launch app
adb shell am start -n com.quickshare.tv/.MainActivity
```

## 📖 Documentation

📄 See [IMPLEMENTATION.md](./IMPLEMENTATION.md) for detailed architecture and protocol breakdown.

## 🙏 Credits

- **NearDrop**
  - Open-source prior art for Quick Share-compatible protocol understanding and behavior validation.
  - [https://github.com/grishka/NearDrop](https://github.com/grishka/NearDrop)
- **rquickshare**
  - Useful reference implementation for wire-format comparisons and interoperability testing.
  - [https://github.com/Martichou/rquickshare/](https://github.com/Martichou/rquickshare/)

## 📄 License

This project is licensed under the [MIT License](./LICENSE).
