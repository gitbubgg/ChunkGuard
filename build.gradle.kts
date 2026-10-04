plugins { java }
group = "dev.chunkguard"; version = "1.0.0"
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
repositories { maven("https://repo.papermc.io/repository/maven-public/") }
dependencies {
    // Set to the exact 26.2 artifact published on the Paper repo.
compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}
