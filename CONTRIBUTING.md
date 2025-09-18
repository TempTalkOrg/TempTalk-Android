# Contributing to TempTalk Android

Thank you for your interest in contributing to TempTalk Android! This document provides guidelines and information for contributors.

## Table of Contents

- [Getting Started](#getting-started)
- [Development Environment](#development-environment)
- [Project Structure](#project-structure)
- [Building the Project](#building-the-project)
- [Code Style](#code-style)
- [Submitting Changes](#submitting-changes)
- [Testing](#testing)
- [Reporting Issues](#reporting-issues)

## Getting Started

Before you begin, please read our [Code of Conduct](CODE_OF_CONDUCT.md) and ensure you understand our project's goals and architecture.

### Prerequisites

- **Android Studio**: Arctic Fox (2020.3.1) or later
- **JDK**: 17 or later (OpenJDK recommended)
- **Android SDK**: API 24-36
- **Gradle**: 8.7.2
- **Kotlin**: 1.9.23

### Required SDK Components

- Android SDK Platform-Tools
- Android SDK Build-Tools
- Android SDK Platform (API 24, 34, 36)
- Android NDK (for native components)

## Development Environment

### 1. Clone the Repository

```bash
   git clone https://github.com/TempTalkOrg/TempTalk-Android.git
cd TempTalkAndroid
```

### 2. Configure Firebase

You'll need to obtain `google-services.json` files and place them in the appropriate directories:

- Development: `app/src/TTDev/google-services.json`
- Production: `app/src/TTOnline/google-services.json`

### 3. Configure Signing (for release builds)

Set up your signing configuration by either:

**Option A: Environment Variables**
```bash
export storePassword="your_store_password"
export keyAlias="your_key_alias"
export keyPassword="your_key_password"
```

**Option B: gradle.properties**
```properties
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
storeFile=/path/to/your/keystore
```

### 4. Import Project

Open Android Studio and import the project. The build system will automatically download dependencies.

## Project Structure

TempTalk Android follows a modular architecture with clear separation of concerns:

```
TempTalkAndroid/
├── app/                    # Main application module
│   ├── src/main/          # Core application code
│   ├── src/TTDev/         # Development environment config
│   └── src/TTOnline/      # Production environment config
├── base/                   # Base components and utilities
│   ├── ui/                # Shared UI components
│   ├── utils/             # Utility classes
│   └── extensions/        # Kotlin extensions
├── network/                # Network layer
│   ├── api/               # API interfaces
│   ├── interceptor/       # Network interceptors
│   └── model/             # Data models
├── chat/                   # Chat functionality
├── call/                   # Voice/video calling
├── login/                  # Authentication module
├── database/               # Database layer (Room + SQLCipher)
├── security/               # Security and encryption
├── video/                  # Video processing
├── image-editor/           # Image editing capabilities
└── selector/               # Media selector
```

### Build Variants

The project supports multiple build variants:

- **Environment**: `TTDev` (development) / `TTOnline` (production)
- **Channel**: `google` / `official` / `insider`
- **Build Type**: `debug` / `release`

## Building the Project

### Basic Build Commands

```bash
# Clean project
./gradlew clean

# Build debug version
./gradlew assembleDebug

# Build specific variant
./gradlew assembleTTOnlineGoogleDebug

# Run tests
./gradlew test

# Run lint checks
./gradlew lint
```

### Installing on Device

```bash
# Install debug APK
adb install app/build/outputs/apk/TTOnlineGoogle/debug/TTOnline-google-v1.9.0-xxx-debug.apk

# Launch application
adb shell am start -n org.difft.chative/.MainActivity
```

## Code Style

### Kotlin Style Guidelines

We follow the official Kotlin coding conventions with some project-specific additions:

1. **Naming Conventions**
   ```kotlin
   // Classes use PascalCase
   class ChatActivity : AppCompatActivity()
   
   // Functions and variables use camelCase
   fun sendMessage(message: String)
   val userName: String
   
   // Constants use UPPER_SNAKE_CASE
   companion object {
       const val MAX_MESSAGE_LENGTH = 1000
   }
   ```

2. **Code Formatting**
   - Use 4 spaces for indentation
   - Line length limit: 120 characters
   - Use ktlint for code formatting
   - Follow Android Kotlin style guide

3. **Documentation**
   ```kotlin
   /**
    * Sends a message to the specified chat room
    * @param message The message content
    * @param chatId The chat room identifier
    * @return Result indicating success or failure
    */
   suspend fun sendMessage(message: String, chatId: String): Result<Unit>
   ```

### Architecture Guidelines

1. **MVVM Pattern**
   ```kotlin
   // ViewModel
   class ChatViewModel @Inject constructor(
       private val chatRepository: ChatRepository
   ) : ViewModel() {
       // Business logic implementation
   }
   
   // Repository
   class ChatRepository @Inject constructor(
       private val apiService: ApiService,
       private val localDatabase: ChatDatabase
   ) {
       // Data management
   }
   ```

2. **Dependency Injection with Hilt**
   ```kotlin
   @Module
   @InstallIn(SingletonComponent::class)
   object NetworkModule {
       @Provides
       @Singleton
       fun provideApiService(): ApiService {
           // API service implementation
       }
   }
   ```

3. **Compose UI Guidelines**
   - Use Compose for new UI components
   - Follow Material Design 3 principles
   - Implement proper state management
   - Use ViewModel for business logic

## Submitting Changes

### Commit Message Format

We use conventional commits for clear and consistent commit messages:

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Commit Types

- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Build process or auxiliary tool changes

### Examples

```bash
feat(chat): add message recall functionality

- Implement message recall API call
- Add recall button UI
- Handle recall status display

Closes #123
```

## Pull Request Process

### 1. Before Submitting

1. Ensure all tests pass
2. Update relevant documentation
3. Follow code style guidelines
4. Test your changes thoroughly

### 2. Creating a Pull Request

When creating a PR, please include:

- Clear description of changes
- Reference to related issues
- Screenshots (for UI changes)
- Testing instructions

### 3. PR Template

```markdown
## Description
Brief description of changes in this PR

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation update

## Testing
- [ ] Unit tests
- [ ] Integration tests
- [ ] Manual testing

## Checklist
- [ ] Code follows project style guidelines
- [ ] Self-review completed
- [ ] Tests added/updated
- [ ] Documentation updated
- [ ] All tests pass
```

### 4. Code Review

- At least one reviewer approval required
- All CI checks must pass
- Address review feedback promptly

## Testing

### Unit Testing

We use JUnit 4 and Mockito for unit testing:

```kotlin
@RunWith(MockitoJUnitRunner::class)
class ChatViewModelTest {
    
    @Mock
    private lateinit var chatRepository: ChatRepository
    
    @InjectMocks
    private lateinit var viewModel: ChatViewModel
    
    @Test
    fun `sendMessage should update UI state`() {
        // Given
        val message = "Hello World"
        
        // When
        viewModel.sendMessage(message)
        
        // Then
        // Verify expected behavior
    }
}
```

### Integration Testing

For integration tests, we use Hilt testing framework:

```kotlin
@HiltAndroidTest
class ChatIntegrationTest {
    
    @get:Rule
    val hiltRule = HiltAndroidRule(this)
    
    @Test
    fun testChatFlow() {
        // Test complete chat flow
    }
}
```

### UI Testing

UI tests use Espresso and Compose testing:

```kotlin
@RunWith(AndroidJUnit4::class)
class ChatActivityTest {
    
    @get:Rule
    val activityRule = ActivityScenarioRule(ChatActivity::class.java)
    
    @Test
    fun testSendMessage() {
        // UI test logic
    }
}
```

## Reporting Issues

### Bug Reports

When reporting bugs, please include:

```markdown
## Bug Description
Brief description of the issue

## Steps to Reproduce
1. Open the app
2. Perform action
3. Observe result

## Expected Behavior
Describe what should happen

## Actual Behavior
Describe what actually happens

## Environment
- Device model: 
- Android version: 
- App version: 
- Network environment: 

## Additional Information
- Log files
- Screenshots
- Other relevant information
```

### Feature Requests

For feature requests:

```markdown
## Feature Description
Detailed description of the requested feature

## Use Case
Explain when this feature would be useful

## Implementation Suggestions
If you have ideas for implementation, please share

## Priority
- [ ] High
- [ ] Medium
- [ ] Low
```

## Third-Party Code

This project incorporates code from the Signal Android project:

- **Video Module**: Video transcoding and processing functionality from Signal
- **Image Editor**: Core image editing components and rendering system from Signal
- **License**: AGPL v3 (compatible with our project license)

When modifying these components, please:
- Maintain compatibility with the original Signal implementation
- Follow Signal's coding standards for these specific modules
- Consider upstream contributions to Signal if improvements are made

## Getting Help

- **Project Maintainers**: [@TempTalkOrg](https://github.com/TempTalkOrg)
- **Technical Discussions**: [Discussions](https://github.com/TempTalkOrg/TempTalk-Android/discussions)
- **Bug Reports**: [Issues](https://github.com/TempTalkOrg/TempTalk-Android/issues)
- **Email**: opensource@temptalk.app

## Resources

- [Android Development Documentation](https://developer.android.com/docs)
- [Kotlin Official Documentation](https://kotlinlang.org/docs/)
- [Jetpack Compose Guide](https://developer.android.com/jetpack/compose)
- [Hilt Dependency Injection](https://dagger.dev/hilt/)

---

Thank you for contributing to TempTalk Android! 🚀