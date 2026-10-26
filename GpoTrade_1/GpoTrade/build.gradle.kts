plugins {
    java
}

group = "fr.gpotrade"
version = "2.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") // Vault API
}

dependencies {
    compileOnly("io.papermc.paper:paper-api") { version { strictly("[26.2.build.0, 26.2.build.134-stable)") } }
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        isTransitive = false // évite le conflit avec l'ancien Bukkit embarqué par Vault
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    // remplace ${version} dans plugin.yml ; le zip du pack est copié tel quel
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
