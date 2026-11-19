# Сборка Полки

- Итог: success
- Импорт: правка наложена
- Gradle: success
- Релиз: создан v0.7.3
- Коммит: 3915cab
- Запуск: https://github.com/lucudar/polka-launcher/actions/runs/44620739107
- Время: 2026-11-19T21:40:01Z

## Ошибки
```
```

## What went wrong
```
```

## Хвост лога
```
> Task :app:preBuild UP-TO-DATE
> Task :app:preReleaseBuild UP-TO-DATE
> Task :app:mergeReleaseJniLibFolders
> Task :app:mergeReleaseNativeLibs
> Task :app:buildKotlinToolingMetadata

> Task :app:stripReleaseDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so.

> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:checkReleaseDuplicateClasses
> Task :app:extractReleaseNativeSymbolTables
> Task :app:mergeReleaseNativeDebugMetadata NO-SOURCE
> Task :app:generateReleaseResValues
> Task :app:checkReleaseAarMetadata
> Task :app:mapReleaseSourceSetPaths
> Task :app:generateReleaseResources
> Task :app:packageReleaseResources
> Task :app:mergeReleaseResources
> Task :app:createReleaseCompatibleScreenManifests
> Task :app:extractDeepLinksRelease
> Task :app:parseReleaseLocalResources
> Task :app:processReleaseMainManifest
> Task :app:processReleaseManifest
> Task :app:processReleaseManifestForPackage
> Task :app:javaPreCompileRelease
> Task :app:mergeReleaseArtProfile
> Task :app:extractProguardFiles
> Task :app:mergeReleaseStartupProfile
> Task :app:mergeReleaseShaders
> Task :app:compileReleaseShaders NO-SOURCE
> Task :app:generateReleaseAssets UP-TO-DATE
> Task :app:mergeReleaseAssets
> Task :app:compressReleaseAssets
> Task :app:extractReleaseVersionControlInfo
> Task :app:processReleaseResources
> Task :app:collectReleaseDependencies
> Task :app:sdkReleaseDependencyData
> Task :app:validateSigningRelease
> Task :app:writeReleaseAppMetadata
> Task :app:convertLinkedResourcesToProtoRelease
> Task :app:writeReleaseSigningConfigVersions
> Task :app:compileReleaseKotlin
> Task :app:compileReleaseJavaWithJavac NO-SOURCE
> Task :app:mergeReleaseGeneratedProguardFiles
> Task :app:processReleaseJavaRes
> Task :app:expandReleaseArtProfileWildcards
> Task :app:mergeReleaseJavaResource
> Task :app:minifyReleaseWithR8
> Task :app:compileReleaseArtProfile
> Task :app:shrinkReleaseRes
> Task :app:convertShrunkResourcesToBinaryRelease
> Task :app:optimizeReleaseResources
> Task :app:packageRelease
> Task :app:createReleaseApkListingFileRedirect
> Task :app:assembleRelease
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run-1795124316971.json

BUILD SUCCESSFUL in 1m 23s
45 actionable tasks: 45 executed
```
