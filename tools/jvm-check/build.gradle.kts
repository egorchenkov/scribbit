// Проверка ядра распознавания на сервере (Linux JVM + sherpa-onnx JNI для linux-aarch64),
// без телефона: тот же Kotlin-код Pipeline/Configs/Formatter/Resampler, что и в APK.
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

kotlin {
    jvmToolchain(17)
    compilerOptions { freeCompilerArgs.add("-Xlambdas=class") }
}

sourceSets.main {
    kotlin.srcDir("../../app/src/main/java")
    kotlin.include(
        "com/k2fsa/**",
        "com/egorchenkov/transcriber/Pipeline.kt",
        "com/egorchenkov/transcriber/Configs.kt",
        "com/egorchenkov/transcriber/Formatter.kt",
        "com/egorchenkov/transcriber/Resampler.kt",
        "android/**",
        "check/**",
    )
}

application {
    mainClass.set("check.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Djava.library.path=" + (System.getenv("SHERPA_JNI") ?: ""),
        "-Xmx2g",
    )
}
