plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.jpa") version "2.4.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlin.plugin.spring") version "2.4.21"
}

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

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.1")
    }
    // MCP's Java SDK 2.1 rather than Spring AI 2.0.1's 2.0, which answers a method it doesn't know with an HTTP 500:
    // ChatGPT starts with the newer revision's server/discover and gave up on that, where "method not found" makes it
    // fall back to initialize (SecurityTest)
    dependencies {
        dependencySet("io.modelcontextprotocol.sdk:2.1.0") {
            entry("mcp")
            entry("mcp-core")
            entry("mcp-json-jackson3")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // implementation("org.springframework.boot:spring-boot-starter-web")
    // implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus") // /actuator/prometheus, scraped in k8s
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // MCP
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
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
}

dependencyManagement {
    imports { mavenBom("org.springframework.ai:spring-ai-bom:2.0.1") }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        javaParameters = true
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
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

// The front-end (fe/), with devenv's Node and Yarn. Only bootJar builds it, into the jar's static/ (Frontend.kt):
// runs and tests do without, as in development Vite serves fe/ itself.
val fe = layout.projectDirectory.dir("fe")

// Looked up on this build's PATH: Java finds commands on the PATH its JVM started with, and the Gradle daemon may have
// started outside the devenv shell, e.g. from IntelliJ
val yarn = providers.environmentVariable("PATH").getOrElse("").split(File.pathSeparator)
    .map { File(it, "yarn") }.firstOrNull { it.canExecute() }?.path ?: "yarn"

val feInstall by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs fe/'s packages, as yarn.lock has them."
    workingDir(fe)
    commandLine(yarn, "install", "--immutable")
    inputs.files(fe.file("package.json"), fe.file("yarn.lock"), fe.file(".yarnrc.yml"))
    // Yarn's record of what it installed - cheaper to check than all of node_modules
    outputs.file(fe.file("node_modules/.yarn-state.yml"))
}

val feTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs fe/'s tests (src/**/__tests__) with vitest."
    dependsOn(feInstall)
    workingDir(fe)
    commandLine(yarn, "test")
}

val feBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Type-checks fe/ and builds it into fe/dist."
    dependsOn(feInstall)
    workingDir(fe)
    commandLine(yarn, "build")
    inputs.files(fileTree(fe) { exclude("node_modules/**", "dist/**", ".git/**", ".yarn/**") })
    outputs.dir(fe.dir("dist"))
}

// Fixed name, so docker/Dockerfile doesn't depend on the version
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "metaos.jar"
    from(feBuild) { into("BOOT-INF/classes/static") }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs = listOf("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")
    systemProperties = System.getProperties().toMap() as Map<String, Any>
}
