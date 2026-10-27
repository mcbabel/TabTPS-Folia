plugins {
  id("tabtps.platform.shadow")
}

indra {
  javaVersions {
    target(17)
    minimumToolchain(25)
  }
}

dependencies {
  implementation(projects.tabtpsCommon)

  compileOnly(libs.foliaApi) {
    exclude(group = "net.kyori")
    exclude(group = "org.slf4j")
  }
  compileOnly(libs.brigadier)
  implementation(libs.adventurePlatformBukkit)
  implementation(libs.adventureTextSerializerPlain)
  implementation(libs.bstatsBukkit)
  implementation(libs.slf4jJdk14)

  implementation(libs.cloudPaper)
}

tasks {
  jar {
    archiveClassifier.set("unshaded")
  }

  shadowJar {
    archiveClassifier.set(null as String?)
    sequenceOf(
      "org.slf4j",
      "org.incendo.cloud",
      "io.leangen.geantyref",
      "net.kyori",
      "org.spongepowered.configurate",
      "org.checkerframework",
      "org.bstats",
      "xyz.jpenilla.pluginbase"
    ).forEach { pkg ->
      relocate(pkg, "${rootProject.group}.${rootProject.name.lowercase()}.lib.$pkg")
    }
    manifest {
      attributes("paperweight-mappings-namespace" to "mojang")
    }
  }

  processResources {
    val replacements = mapOf(
      "version" to version.toString(),
      "description" to project.description,
      "github" to "https://github.com/MCbabel/TabTPS-Folia"
    )
    inputs.properties(replacements)
    filesMatching("plugin.yml") {
      expand(replacements)
    }
  }
}

tabTPSPlatform {
  productionJar.set(tasks.shadowJar.flatMap { it.archiveFile })
}

publishMods.modrinth {
  minecraftVersions.addAll("1.20", "1.20.1", "1.20.2", "1.20.4", "1.20.6", "1.21.4", "1.21.5", "1.21.6", "1.21.8", "1.21.11", "26.1.2", "26.2", "26.3")
  modLoaders.add("folia")
  modLoaders.add("purpur")
  modLoaders.add("spigot")
}
