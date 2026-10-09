plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.14"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "lv.sknarovs.futsalcalendar.Main"
}

tasks.test {
    useJUnitPlatform()
}

graalvmNative {
    binaries.named("main") {
        imageName = "lff-futsal-calendar"
        // Baseline ARMv8.0 so the binary runs on a Raspberry Pi 3 (Cortex-A53).
        buildArgs.add("-march=compatibility")
    }
}
