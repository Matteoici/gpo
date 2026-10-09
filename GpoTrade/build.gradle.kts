plugins {
    java
}

group = "fr.gpotrade"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") // Vault API
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        isTransitive = false // évite le conflit avec l'ancien Bukkit embarqué par Vault
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
