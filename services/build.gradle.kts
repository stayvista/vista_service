plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    id("org.springframework.boot") version "4.0.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.devoceanblue.stayvista"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.apply("org.jetbrains.kotlin.jvm")

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}
