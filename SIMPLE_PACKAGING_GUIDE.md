# TempTalk Android Packaging Guide

This guide provides detailed instructions on how to build the TempTalk Android project from scratch, ultimately generating the `TTOnline-google-v1.9.0-xxx-debug.apk` file.

## Table of Contents

- [Environment Setup](#environment-setup)
- [Project Configuration](#project-configuration)
- [Build Steps](#build-steps)
- [Verification](#verification)
- [Troubleshooting](#troubleshooting)

## Environment Setup

### Step 1: Install Java 17

#### macOS Users
```bash
# Install using Homebrew
brew install openjdk@17

# Configure environment variables
echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home' >> ~/.zshrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.zshrc
source ~/.zshrc

# Verify installation
java -version
```

#### Windows Users
1. Download OpenJDK 17: https://adoptium.net/
2. Install to default path
3. Set environment variables:
   - `JAVA_HOME`: `C:\Program Files\Eclipse Adoptium\jdk-17.x.x.x-hotspot`
   - `PATH`: Add `%JAVA_HOME%\bin`

#### Linux Users
```bash
# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-17-jdk

# CentOS/RHEL
sudo yum install java-17-openjdk-devel

# Verify installation
java -version
```

### Step 2: Install Android SDK

#### Method 1: Through Android Studio (Recommended)
1. Download and install [Android Studio](https://developer.android.com/studio)
2. Open Android Studio, go to SDK Manager
3. Install the following components:
   - Android SDK Platform-Tools
   - Android SDK Build-Tools (34.0.0)
   - Android SDK Platform (API 24, 34, 36)

#### Method 2: Command Line Installation
```bash
# macOS
brew install --cask android-commandlinetools

# Set environment variables
echo 'export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools' >> ~/.zshrc
echo 'export PATH=$ANDROID_HOME/platform-tools:$PATH' >> ~/.zshrc
source ~/.zshrc

# Install required components
sdkmanager "platform-tools" "platforms;android-24" "platforms;android-34" "platforms;android-36" "build-tools;34.0.0"
```

### Step 3: Verify Environment

```bash
# Check Java version (should be 17.x.x)
java -version

# Check Android SDK
echo $ANDROID_HOME
ls $ANDROID_HOME

# Check adb
adb version
```

## Project Configuration

### Step 4: Clone Project

```bash
# Clone project to local
git clone https://github.com/TempTalkOrg/TempTalk-Android.git
cd TempTalkAndroid

# Check project structure
ls -la
```

### Step 5: Configure local.properties

Create or edit `local.properties` file:

```properties
# Android SDK path
sdk.dir=/opt/homebrew/share/android-commandlinetools

# Note: Windows user path example
# sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk

# Note: Linux user path example  
# sdk.dir=/home/yourname/Android/Sdk
```

### Step 6: Configure Firebase (Required)

#### Get google-services.json file
1. Login to [Firebase Console](https://console.firebase.google.com/)
2. Select or create project
3. Add Android app
4. Download `google-services.json` file

#### Place configuration files
```bash
# Create directory structure
mkdir -p app/src/TTDev
mkdir -p app/src/TTOnline

# Copy configuration files (replace with your actual files)
cp /path/to/your/google-services.json app/src/TTDev/
cp /path/to/your/google-services.json app/src/TTOnline/

# Verify files exist
ls -la app/src/TTDev/google-services.json
ls -la app/src/TTOnline/google-services.json
```

### Step 7: Configure Signing Key (Optional)

If you need to build release versions, configure signing key:

```bash
# Set environment variables
export storePassword="your_store_password"
export keyAlias="your_key_alias"  
export keyPassword="your_key_password"

# Or create gradle.properties file
echo "storePassword=your_store_password" >> gradle.properties
echo "keyAlias=your_key_alias" >> gradle.properties
echo "keyPassword=your_key_password" >> gradle.properties
```

## Build Steps

### Step 8: Environment Check

Manually check the following items:

```bash
# 1. Check Java version
java -version
# Should display: openjdk version "17.x.x"

# 2. Check Android SDK
echo $ANDROID_HOME
# Should display: /opt/homebrew/share/android-commandlinetools (macOS)
# or: C:\Users\YourName\AppData\Local\Android\Sdk (Windows)

# 3. Check Gradle
./gradlew --version
# Should display: Gradle 8.9

# 4. Check Firebase configuration
ls -la app/src/TTDev/google-services.json
ls -la app/src/TTOnline/google-services.json
# Both files should exist
```

### Step 9: Clean Project

```bash
# Clean previous build files
./gradlew clean
```

Expected output:
```
BUILD SUCCESSFUL in 15s
13 actionable tasks: 13 executed
```

### Step 10: Build Specific Variant

```bash
# Build TTOnline-google debug version
./gradlew assembleTTOnlineGoogleDebug
```

**Important Note**: This command will build the production Google channel debug version, generating the APK file we need.

Expected output:
```
BUILD SUCCESSFUL in 2m 55s
320 actionable tasks: 315 executed, 5 up-to-date
```

### Step 11: Verify Build Results

```bash
# Check generated APK files
find app/build/outputs -name "*.apk" -exec ls -lh {} \;
```

Expected output:
```
-rw-r--r--@ 1 user  staff   157M  [time] app/build/outputs/apk/TTOnlineGoogle/debug/TTOnline-google-v1.9.0-373471-202509171631-debug.apk
```

## Verification

### Step 12: Check APK Information

```bash
# View APK details
ls -la app/build/outputs/apk/TTOnlineGoogle/debug/

# Check APK size (should be around 157MB)
du -h app/build/outputs/apk/TTOnlineGoogle/debug/*.apk
```

### Step 13: Test Installation (Optional)

```bash
# Connect Android device and enable USB debugging
adb devices

# Install APK to device
adb install app/build/outputs/apk/TTOnlineGoogle/debug/TTOnline-google-v1.9.0-373471-202509171631-debug.apk

# Launch application
adb shell am start -n org.difft.chative/.MainActivity
```

## Complete Command Sequence

Here's the complete command sequence that can be copied and pasted:

```bash
# 1. Enter project directory
cd TempTalkAndroid

# 2. Check environment
java -version
echo $ANDROID_HOME
./gradlew --version

# 3. Clean project
./gradlew clean

# 4. Build specific variant
./gradlew assembleTTOnlineGoogleDebug

# 5. Verify results
find app/build/outputs -name "*.apk" -exec ls -lh {} \;

# 6. View APK path
echo "APK file location:"
echo "$(pwd)/app/build/outputs/apk/TTOnlineGoogle/debug/TTOnline-google-v1.9.0-*-debug.apk"
```

## Troubleshooting

### Q1: Java Version Error
**Problem**: `Unsupported Java. Your build is currently configured to use Java 8`
**Solution**: Ensure Java 17 is installed and JAVA_HOME environment variable is correctly configured

### Q2: Android SDK Not Found
**Problem**: `SDK location not found`
**Solution**: Check that the sdk.dir path in local.properties file is correct

### Q3: Firebase Configuration Missing
**Problem**: `File google-services.json is missing`
**Solution**: Ensure google-services.json file is placed in app/src/TTOnline/ directory

### Q4: Build Time Too Long
**Problem**: Build process takes more than 10 minutes
**Solution**: 
- Ensure network connection is stable
- Use domestic mirror sources
- Increase Gradle memory: `export GRADLE_OPTS="-Xmx4g"`

### Q5: Dependency Download Failed
**Problem**: `Could not download xxx`
**Solution**:
```bash
# Clean Gradle cache
./gradlew clean --refresh-dependencies

# Or use offline mode
./gradlew assembleTTOnlineGoogleDebug --offline
```

## Build Failure Diagnosis

1. **Check Logs**
   ```bash
   # View detailed build logs
   ./gradlew assembleTTOnlineGoogleDebug --info
   ```

2. **Clean Cache**
   ```bash
   # Clean all cache
   ./gradlew clean
   rm -rf .gradle
   rm -rf app/build
   ```

3. **Re-download Dependencies**
   ```bash
   # Force refresh dependencies
   ./gradlew assembleTTOnlineGoogleDebug --refresh-dependencies
   ```

## Performance Optimization

1. **Increase Memory**
   ```bash
   # Add to gradle.properties
   echo "org.gradle.jvmargs=-Xmx8g" >> gradle.properties
   ```

2. **Enable Parallel Build**
   ```bash
   # Add to gradle.properties
   echo "org.gradle.parallel=true" >> gradle.properties
   ```

3. **Enable Build Cache**
   ```bash
   # Add to gradle.properties
   echo "org.gradle.caching=true" >> gradle.properties
   ```

## Build Time Reference

| Environment | First Build | Incremental Build | Clean Build |
|-------------|-------------|-------------------|-------------|
| High-performance machine | 3-5 minutes | 30 seconds-1 minute | 2-3 minutes |
| Regular machine | 5-8 minutes | 1-2 minutes | 3-5 minutes |
| Low-configuration machine | 8-15 minutes | 2-3 minutes | 5-8 minutes |

## Success Indicators

When you see the following output, the build is successful:

```bash
BUILD SUCCESSFUL in 2m 55s
320 actionable tasks: 315 executed, 5 up-to-date
```

And the APK file is generated:
```
app/build/outputs/apk/TTOnlineGoogle/debug/TTOnline-google-v1.9.0-373471-202509171631-debug.apk
```

## Related Documentation

- [README.md](README.md) - Project introduction and quick start
- [CONTRIBUTING.md](CONTRIBUTING.md) - How to contribute to the project

## Getting Help

If you encounter any issues, please:

1. Check the troubleshooting section of this document
2. Search project [Issues](https://github.com/TempTalkOrg/TempTalk-Android/issues)
3. Contact the development team: opensource@temptalk.app

---

**Congratulations!** You have successfully built the TTOnline-google version of TempTalk Android! 🎊