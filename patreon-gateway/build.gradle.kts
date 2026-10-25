plugins {
    kotlin("jvm") version "2.4.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlin.plugin.spring") version "2.4.21"
}

// As MetaOS's build.gradle.kts: Jackson's latest patches rather than Spring Boot 4.1.2's
extra["jackson-bom.version"] = "3.1.8"
extra["jackson-2-bom.version"] = "2.21.8"

group = "si.ogrodje.patreon"
version = "0.0.1"

repositories {
    mavenCentral()
}

// Playwright's version is also the browsers' in its image (docker/patreon-gateway.Dockerfile): keep them the same
val playwrightVersion = "1.64.0"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Patreon's web app, signed in as the bot, for what its API can't do (Browser.kt)
    implementation("com.microsoft.playwright:playwright:$playwrightVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        javaParameters = true
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

// The gateway, not Login.kt's main
springBoot {
    mainClass = "si.ogrodje.patreon.PatreonGatewayKt"
}

// Fixed name, so docker/patreon-gateway.Dockerfile doesn't depend on the version
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "patreon-gateway.jar"
}

// Signs the bot in to Patreon in a browser you can see, and saves its session (Login.kt, README.md)
tasks.register<JavaExec>("login") {
    group = "application"
    description = "Signs the bot in to Patreon in a visible browser and saves the session to state/session.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "si.ogrodje.patreon.LoginKt"
    args = listOf(providers.gradleProperty("session").getOrElse("state/session.json"))
    standardInput = System.`in`
}
