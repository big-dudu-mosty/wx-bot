package buildlogic

import org.gradle.api.Project
import java.io.File

data class Versions(
    val appVersionCode: Int,
    val appVersionName: String,
    val devVersionCode: Int,
    val devVersionName: String,
    val target: Int,
    val mini: Int,
    val compile: Int,
    val buildTool: String,
)

private const val VersionsKey = "projectVersions"

fun Project.initVersions(file: File) {
    require(file.isFile) { "Version file does not exist: ${file.absolutePath}" }
    val json = file.readText()
    rootProject.extensions.extraProperties.set(VersionsKey, Versions(
        appVersionCode = json.intValue("appVersionCode"),
        appVersionName = json.stringValue("appVersionName"),
        devVersionCode = json.intValue("devVersionCode"),
        devVersionName = json.stringValue("devVersionName"),
        target = json.intValue("target"),
        mini = json.intValue("mini"),
        compile = json.intValue("compile"),
        buildTool = json.stringValue("buildTool"),
    ))
}

val Project.versions: Versions
    get() = rootProject.extensions.extraProperties.get(VersionsKey) as Versions

private fun String.intValue(name: String): Int = value(name).toInt()

private fun String.stringValue(name: String): String = value(name)

private fun String.value(name: String): String {
    val match = Regex("\\\"$name\\\"\\s*:\\s*(?:\\\"([^\\\"]+)\\\"|(\\d+))").find(this)
        ?: error("Missing version field: $name")
    return match.groups[1]?.value ?: match.groups[2]?.value ?: error("Invalid version field: $name")
}
