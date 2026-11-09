plugins { java }

group = "dev.npcdialogue"
version = "1.0.0"

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) } // версия Java под Paper 26.x

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Укажи ту же версию paper-api, что и в твоих других плагинах
    compileOnly("io.papermc.paper:paper-api:26.3-R0.1-SNAPSHOT")
    // BetterModel вызывается через рефлексию — jar для сборки НЕ нужен
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
