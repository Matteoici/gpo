plugins {
    java
}

group = "fr.smpboss"
version = "1.14.4"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0") { isTransitive = false }
    compileOnly("io.papermc.paper:paper-api") { version { strictly("[26.2.build.0, 26.2.build.134-stable)") } }
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
