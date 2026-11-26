plugins {
    kotlin("jvm") version "2.4.21"
    id("org.springframework.boot") version "4.2.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlin.plugin.spring") version "2.4.21"
}

group = "si.ogrodje.scrapers"
version = "0.0.1"

repositories {
    mavenCentral()
}

// Playwright's version is also the browser container's in k8s (k8s/base/deployment-scrapers.yaml), as
// patreon-gateway's: keep them alike
val playwrightVersion = "1.65.1"

// MetaOS's (../build.gradle.kts), whose workflow calls the scrapers' activities
val temporalVersion = "1.41.1"

// Playwright's driver-bundle has Node.js for five platforms; the image runs on linux/amd64 only, so the boot jar gets
// Linux's alone, as patreon-gateway's does
val driverBundle = configurations.create("driverBundle") { isTransitive = false }

// New Relic's Java agent, which the image runs the scrapers with, as MetaOS's (../newrelic/newrelic.yml)
val newRelicAgent = configurations.create("newRelicAgent") { isTransitive = false }

dependencies {
    driverBundle("com.microsoft.playwright:driver-bundle:$playwrightVersion")
    newRelicAgent("com.newrelic.agent.java:newrelic-agent:9.5.0")
}

val newRelic = tasks.register<Sync>("newRelic") {
    group = "build"
    description = "Puts New Relic's Java agent and ../newrelic/newrelic.yml in build/newrelic, for the image."
    from(newRelicAgent) { rename { "newrelic.jar" } }
    from("../newrelic/newrelic.yml")
    into(layout.buildDirectory.dir("newrelic"))
}

val linuxDriverBundle = tasks.register<Jar>("linuxDriverBundle") {
    description = "Playwright's driver-bundle with only the Node.js for linux/amd64, for the boot jar."
    destinationDirectory = layout.buildDirectory.dir("driver-bundle")
    archiveFileName = "driver-bundle-$playwrightVersion-linux.jar"
    from({ zipTree(driverBundle.singleFile) }) {
        exclude("META-INF/MANIFEST.MF")
        exclude("driver/mac/**", "driver/mac-arm64/**", "driver/linux-arm64/**", "driver/win32_x64/**")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Pages (jsoup), calendars (ical4j), and the few sites that only show their events to a browser (Playwright)
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("org.mnode.ical4j:ical4j:4.4.0")
    implementation("com.microsoft.playwright:playwright:$playwrightVersion")

    // Scraping is an activity of MetaOS's events workflow (EventsSync.kt in MetaOS), which the scrapers work on
    // (Activities.kt); as MetaOS's, whose version this has to stay at
    implementation("io.temporal:temporal-spring-boot-starter:$temporalVersion")
    implementation("io.temporal:temporal-sdk:$temporalVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation("io.temporal:temporal-testing:$temporalVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
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
    // As in the image: a parser that leans on the machine's time zone, not Ljubljana's, fails here too
    systemProperty("user.timezone", "UTC")
}

springBoot {
    mainClass = "si.ogrodje.scrapers.ScrapersKt"
}

// Fixed name, so docker/scrapers.Dockerfile doesn't depend on the version; with the agent, which the image takes too
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "scrapers.jar"
    finalizedBy(newRelic)
    setClasspath(classpath!!.filter { it.name != "driver-bundle-$playwrightVersion.jar" } + files(linuxDriverBundle))
}

// The same jar from the same sources, so the image of unchanged scrapers is the same image, which a deploy leaves be
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Scrapes one URL as the worker would, and prints what it found: ./gradlew -p scrapers scrape --args='<url> [field]'
tasks.register<JavaExec>("scrape") {
    group = "application"
    description = "Scrapes one URL with the parser that takes it and prints the events it found."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "si.ogrodje.scrapers.ScrapeKt"
}
