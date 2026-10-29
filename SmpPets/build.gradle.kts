plugins {
    java
}

group = "fr.smppets"
version = "1.5.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") // Vault
}

dependencies {
    // le build 134 de Paper référence une bibliothèque (adventure 5.2.0) pas encore publiée : on reste avant
    compileOnly("io.papermc.paper:paper-api") { version { strictly("[26.2.build.0, 26.2.build.134-stable)") } }
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
