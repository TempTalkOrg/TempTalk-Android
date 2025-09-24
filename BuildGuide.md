# TempTalk Android Build Guide

This guide provides instructions for building TempTalk Android from source code.

## Prerequisites

Before building, ensure you have the following prerequisites installed:

### Step 1: Install Java 17

### Step 2: Install Android SDK

### Step 3: Install Git

### Step 4: Configure Firebase

Place `google-services.json` files in:
- `app/src/TTDev/google-services.json`
- `app/src/TTOnline/google-services.json`

## Quick Start

After installing all prerequisites, execute the following commands to build the project:

```bash
# 1. Clone the repository (execute in any directory)
git clone https://github.com/TempTalkOrg/TempTalk-Android.git
cd TempTalk-Android

# 2. Create local.properties file in project root directory with content:
# sdk.dir=your_android_sdk_path

# Linux:
echo "sdk.dir=/home/username/Android/Sdk" > local.properties

# macOS:
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties

# Windows:
echo sdk.dir=C:\\Android\\Sdk > local.properties

# 3. Clean and build (execute in project root directory)
./gradlew clean
./gradlew assembleTTOnlineGoogleDebug

# 4. Find the generated APK file
# APK file location: app/build/outputs/apk/TTOnlineGoogle/debug/
ls app/build/outputs/apk/TTOnlineGoogle/debug/
```

---

**Congratulations!** You have successfully built TempTalk Android! 🎉