plugins {
    java
}

group = "fr.smpsalles"
version = "1.1.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api") { version { strictly("[26.2.build.0, 26.2.build.134-stable)") } }
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.5.0")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0") { isTransitive = false }
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
