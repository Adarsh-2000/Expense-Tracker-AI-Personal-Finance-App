# Gradle Wrapper Note

This project ships a complete Gradle wrapper:

- `gradlew` (POSIX shell — already marked executable)
- `gradlew.bat` (Windows)
- `gradle/wrapper/gradle-wrapper.properties` (declares Gradle 8.6 — `distributionUrl=https://services.gradle.org/distributions/gradle-8.6-bin.zip`)
- `gradle/wrapper/gradle-wrapper.jar` (the official Gradle 8.6 bootstrap jar — **present**)

On first run, `./gradlew` downloads the Gradle 8.6 distribution named in
`gradle-wrapper.properties` into `~/.gradle/` and uses it. Android Studio does the
same automatically on project sync.

## Verifying the wrapper jar

```
unzip -l gradle/wrapper/gradle-wrapper.jar | grep GradleWrapperMain
```
should list `org/gradle/wrapper/GradleWrapperMain.class`.

## If you ever need to regenerate it

With a local Gradle install: `gradle wrapper --gradle-version 8.6`. Android Studio
also regenerates it on demand.
