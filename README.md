# TempTalk Android

TempTalk Android is a modern instant messaging application built with Android native development, using Kotlin and Jetpack Compose UI framework.

🌐 **Official Website**: [https://www.temptalk.app/](https://www.temptalk.app/)

## Features

- **Instant Messaging**: Support for text, voice, images, videos and other message types
- **Voice & Video Calls**: High-quality audio and video calling functionality
- **Group Chats**: Create and manage group conversations
- **End-to-End Encryption**: Secure communication based on Signal protocol
- **Cross-Platform**: Native Android application
- **Modern UI**: Responsive interface built with Jetpack Compose

## Architecture

### Core Technology Stack
- **Language**: Kotlin 1.9.23
- **UI Framework**: Jetpack Compose
- **Architecture**: MVVM + Repository Pattern
- **Dependency Injection**: Hilt (Dagger)
- **Networking**: Retrofit + OkHttp
- **Database**: Room + SQLCipher
- **Image Loading**: Glide
- **Media**: ExoPlayer, WebRTC

### Project Structure
```
TempTalkAndroid/
├── app/                    # Main application module
├── base/                   # Base components and utilities
├── network/                # Network layer
├── login/                  # Authentication module
├── chat/                   # Chat functionality
├── call/                   # Voice/video calling
├── video/                  # Video processing
├── image-editor/           # Image editing
├── selector/               # Media selector
├── security/               # Security and encryption
└── database/               # Database layer
```

## Getting Started

### Requirements

- **Android Studio**: Arctic Fox or later
- **JDK**: 17 or later
- **Android SDK**: API 24+ (Android 7.0)
- **Gradle**: 8.7.2
- **Kotlin**: 1.9.23

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/TempTalkOrg/TempTalk-Android.git
   cd TempTalkAndroid
   ```

2. **Configure Firebase**
   - Place `google-services.json` files in the appropriate directories:
     - Development: `app/src/TTDev/google-services.json`
     - Production: `app/src/TTOnline/google-services.json`

3. **Configure Android SDK**
   - Create `local.properties` file:
   ```properties
   sdk.dir=/path/to/your/android-sdk
   ```

4. **Build the project**
   ```bash
   # Build specific variant
   ./gradlew assembleTTOnlineGoogleDebug
   ```

## Build Variants

The project supports multiple build variants:

### Environment Variants
- **TTDev**: Development environment (`org.difft.chative.test`)
- **TTOnline**: Production environment (`org.difft.chative`)

### Channel Variants
- **google**: Google Play channel
- **official**: Official channel
- **insider**: Insider channel

### Build Types
- **debug**: Debug version
- **release**: Release version

## Development Tools

### Common Commands

```bash
# Clean project
./gradlew clean

# Build debug version
./gradlew assembleDebug

# Build release version
./gradlew assembleRelease

# Generate AAB file
./gradlew bundleRelease

# Run tests
./gradlew test

# Code analysis
./gradlew lint
```

## Installation and Running

### Using ADB

```bash
# Install APK
adb install app/build/outputs/apk/TTOnlineGoogle/debug/TTOnline-google-v1.9.0-xxx-debug.apk

# Launch application
adb shell am start -n org.difft.chative/.MainActivity
```

## Configuration

### Version Management
- **Version Name**: 1.9.0
- **Version Code**: Auto-generated based on timestamp
- **Compile SDK**: 36
- **Target SDK**: 34
- **Min SDK**: 24

### Dependency Management
The project uses `libs.versions.toml` for centralized dependency management to ensure version consistency.

### Signing Configuration
Release builds require signing key configuration, supported through environment variables or gradle.properties file.

## Documentation

- [Packaging Guide](SIMPLE_PACKAGING_GUIDE.md) - Detailed packaging instructions
- [Contributing Guide](CONTRIBUTING.md) - How to contribute to the project

## Contributing

We welcome all forms of contributions! Please check [CONTRIBUTING.md](CONTRIBUTING.md) to learn how to participate in project development.

### Development Workflow
1. Fork the project
2. Create a feature branch
3. Commit your changes
4. Create a Pull Request

## Acknowledgments

This project incorporates code from the following open source projects:

- **[Signal Android](https://github.com/signalapp/Signal-Android)** - Video transcoding and image editing components
  - Video processing and transcoding functionality
  - Image editor core components and rendering system
  - Licensed under AGPL v3

We thank the Signal team for their excellent work on secure messaging and their contributions to the open source community.

## License

This project is licensed under the GNU Affero General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

## Support

If you encounter any issues or have questions, please:

1. Check the [Packaging Guide](SIMPLE_PACKAGING_GUIDE.md) for common issues
2. Search [Issues](https://github.com/TempTalkOrg/TempTalk-Android/issues)
3. Create a new Issue
4. Contact the development team: opensource@temptalk.app

## Links

- [Official Website](https://www.temptalk.app/) - TempTalk official website
- [Project Homepage](https://github.com/TempTalkOrg/TempTalk-Android)

---

**TempTalk Android** - Making communication simpler and connections more secure.
