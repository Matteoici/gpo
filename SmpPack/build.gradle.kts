plugins {
    java
}

group = "fr.smppack"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // le build 134 de Paper référence une bibliothèque (adventure 5.2.0) pas encore publiée : on reste avant
    compileOnly("io.papermc.paper:paper-api") { version { strictly("[26.2.build.0, 26.2.build.134-stable)") } }
    // Netty est fourni par le serveur : sert à répondre au téléchargement du pack sur le port du jeu
    compileOnly("io.netty:netty-transport:4.1.118.Final")
    compileOnly("io.netty:netty-buffer:4.1.118.Final")
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
