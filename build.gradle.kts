plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.jpa") version "2.4.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlin.plugin.spring") version "2.4.21"
}

// Jackson's latest patches rather than Spring Boot 4.1.2's 3.1.7 / 2.21.7: those already fix the parser's denial of
// service (CVE-2026-89407, -89425 and others), and these harden it further, e.g. bound the length of numbers coerced
// from strings. Drop these once a Boot release manages these versions or later.
extra["jackson-bom.version"] = "3.1.8"
extra["jackson-2-bom.version"] = "2.21.8"

group = "si.ogrodje.metaos"
version = "0.0.1"

repositories {
    mavenCentral()
    // Koofr's Java SDK isn't on Maven Central; JitPack builds it from its GitHub release tag
    exclusiveContent {
        forRepository { maven("https://jitpack.io") }
        filter { includeGroup("com.github.koofr") }
    }
}

// Spring AI 2.0.2 brings MCP's Java SDK 2.1, which answers a method it doesn't know with "method not found" rather than
// an HTTP 500: ChatGPT starts with the newer revision's server/discover and falls back to initialize (SecurityTest)
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.2")
    }
}

// New Relic's Java agent (newrelic/newrelic.yml), which the image runs the app with, and its API, which does nothing
// without it (NewRelic.kt). 8.25 is the first for Java 25.
val newRelicVersion = "9.5.0"
val newRelicAgent = configurations.create("newRelicAgent") { isTransitive = false }

val temporalVersion = "1.40.0"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // implementation("org.springframework.boot:spring-boot-starter-web")
    // implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus") // /actuator/prometheus, scraped in k8s

    newRelicAgent("com.newrelic.agent.java:newrelic-agent:$newRelicVersion")
    implementation("com.newrelic.agent.java:newrelic-api:$newRelicVersion")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // MCP
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    // implementation("org.springframework.boot:spring-boot-starter-mustache")

    // GraphQL at /graphql, for the front-end (GraphQL.kt)
    implementation("org.springframework.boot:spring-boot-starter-graphql")

    implementation("tools.jackson.module:jackson-module-kotlin")

    // Security - MCP endpoint is an OAuth2 resource server, Keycloak is the authorization server
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springaicommunity:mcp-server-security:0.1.14")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")

    // runtimeOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Native macOS DNS resolver for Netty (avoids the MacOSDnsServerAddressStreamProvider fallback warning).
    // runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.15.Final:osx-aarch_64")

    // PG
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Not only at runtime: Changes.kt listens for Postgres's notifications through its API
    implementation("org.postgresql:postgresql")

    // Google Drive and Gmail, as Ogrodje's bot account (Google.kt)
    implementation("com.google.auth:google-auth-library-oauth2-http:1.54.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20261011-2.0.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20261005-2.0.0")
    // Google Cloud Storage, as a service account per environment (Storage.kt)
    implementation("com.google.cloud:google-cloud-storage:2.75.0")
    // Pictures made smaller for the public bucket (Images.kt); TwelveMonkeys reads what ImageIO can't: CMYK JPEGs, WebP
    implementation("net.coobird:thumbnailator:0.4.21")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.15.3")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.3")

    // Koofr, as Ogrodje's bot account (Koofr.kt)
    implementation("com.github.koofr:java-koofr:3.8.10")
    // Dropbox, as Ogrodje's bot account (Dropbox.kt)
    implementation("com.dropbox.core:dropbox-core-sdk:8.0.2")

    // Durable workflows on Temporal (Workflows.kt): MetaOS is a worker, its activities its own beans
    implementation("io.temporal:temporal-spring-boot-starter:$temporalVersion")
    implementation("io.temporal:temporal-sdk:$temporalVersion")
    implementation("io.temporal:temporal-kotlin:$temporalVersion")
    // Temporal's payloads are Jackson 2's, whose Kotlin module makes Kotlin data classes of them (Workflows.kt)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation("io.temporal:temporal-testing:$temporalVersion")
    // Agents in workflows (Agents.kt): Spring AI's ChatClient, whose model calls and MCP tool calls Temporal's plugin
    // makes activities; the model through OpenRouter, which speaks OpenAI's API, and the tools MetaOS's own MCP tools,
    // as the agents' service account (client credentials)
    implementation("io.temporal:spring-ai:0.1.1")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    // Speech-to-Text, which transcribes episodes' audio from the bucket (Transcription.kt)
    implementation("com.google.cloud:google-cloud-speech:4.94.0")
}

// Without Mockito, which no test uses and the test starters bring: Spring's MockitoResetTestExecutionListener would
// load it before every test, and its inline mock maker attach byte-buddy's agent to the JVM, which Java warns will soon
// be disallowed
configurations.matching { it.name.startsWith("test") }.configureEach {
    exclude(group = "org.mockito")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        javaParameters = true   // tool JSON schemas need real parameter names
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // JVMs at once, each with its own Spring contexts and their Postgres: two by default, -PtestForks=<n> for more
    // (CI, .github/workflows)
    maxParallelForks = providers.gradleProperty("testForks").map(String::toInt).getOrElse(2)
    // Conscrypt (Cloud Storage's TLS) loads its native library, which Java warns will soon need this
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // -PmcpTools=update (or breaking) has McpToolsTest write the MCP tools' snapshot and version: `just mcp-tools`
    systemProperty("metaos.mcpTools", providers.gradleProperty("mcpTools").getOrElse(""))
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

// The front-end (fe/), with devenv's Node and Yarn: a service of its own, which Nuxt builds into fe/.output for its
// image (docker/web.Dockerfile). The app's runs, tests and jar do without it.
val fe = layout.projectDirectory.dir("fe")

// Looked up on this build's PATH: Java finds commands on the PATH its JVM started with, and the Gradle daemon may have
// started outside the devenv shell, e.g. from IntelliJ
val yarn = providers.environmentVariable("PATH").getOrElse("").split(File.pathSeparator)
    .map { File(it, "yarn") }.firstOrNull { it.canExecute() }?.path ?: "yarn"

val feInstall = tasks.register<Exec>("feInstall") {
    group = "build"
    description = "Installs fe/'s packages, as yarn.lock has them."
    workingDir(fe)
    commandLine(yarn, "install", "--immutable")
    inputs.files(fe.file("package.json"), fe.file("yarn.lock"), fe.file(".yarnrc.yml"))
    // Yarn's record of what it installed - cheaper to check than all of node_modules
    outputs.file(fe.file("node_modules/.yarn-state.yml"))
}

tasks.register<Exec>("feTest") {
    group = "verification"
    description = "Runs fe/'s tests (src/**/__tests__) with vitest."
    dependsOn(feInstall)
    workingDir(fe)
    commandLine(yarn, "test")
}

tasks.register<Exec>("feBuild") {
    group = "build"
    description = "Type-checks fe/ and builds it into fe/.output, for its image."
    dependsOn(feInstall)
    workingDir(fe)
    commandLine(yarn, "build")
    inputs.files(fileTree(fe) { exclude("node_modules/**", ".nuxt/**", ".output/**", ".data/**", ".git/**", ".yarn/**") })
    outputs.dir(fe.dir(".output"))
}

// New Relic's Java agent and its newrelic.yml, in build/newrelic: beside the app in its image (docker/Dockerfile), and
// for running it in development with -javaagent:build/newrelic/newrelic.jar
val newRelic = tasks.register<Sync>("newRelic") {
    group = "build"
    description = "Puts New Relic's Java agent and newrelic/newrelic.yml in build/newrelic."
    from(newRelicAgent) { rename { "newrelic.jar" } }
    from("newrelic/newrelic.yml")
    into(layout.buildDirectory.dir("newrelic"))
}

// Fixed name, so docker/Dockerfile doesn't depend on the version; with the agent, which the image takes too
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "metaos.jar"
    finalizedBy(newRelic)
}

// The same jar from the same sources, so the image of an unchanged app is the same image, which a deploy leaves be
// (.github/workflows/deploy.yml)
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs = listOf("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005", "--enable-native-access=ALL-UNNAMED")
    systemProperties = System.getProperties().toMap() as Map<String, Any>
}
