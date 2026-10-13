plugins {
    java
}

group = "fr.smpmarket"
version = "1.3.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") // Vault
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
