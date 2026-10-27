import net.kyori.indra.git.IndraGitExtension
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.the

val Project.releaseNotes: Provider<String>
  get() = providers.environmentVariable("RELEASE_NOTES")

fun Project.lastCommitHash(): String =
  the<IndraGitExtension>().commit().orNull?.name?.substring(0, 7)
    ?: error("Could not determine git commit hash")

fun Project.decorateVersion() {
  val ver = version as String
  version = if (ver.endsWith("-SNAPSHOT")) {
    "$ver+${lastCommitHash()}"
  } else {
    ver
  }
}
