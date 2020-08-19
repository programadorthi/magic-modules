package io.labs.dotanuki.magicmodules.internal

import io.labs.dotanuki.magicmodules.internal.model.GradleBuildScript
import io.labs.dotanuki.magicmodules.internal.model.GradleFoundModule
import io.labs.dotanuki.magicmodules.internal.model.GradleModuleType
import io.labs.dotanuki.magicmodules.internal.model.GradleProjectStructure
import io.labs.dotanuki.magicmodules.internal.model.ParserRawContent
import io.labs.dotanuki.magicmodules.internal.util.e
import io.labs.dotanuki.magicmodules.internal.util.i
import io.labs.dotanuki.magicmodules.internal.util.logger
import java.io.File

internal class ProjectStructureParser(
    private val parserRawContent: ParserRawContent
) {
    fun parse(rootFolder: File): GradleProjectStructure =
        when {
            rootFolder.isDirectory -> {
                val foundedScripts = rootFolder.walkTopDown()
                    .maxDepth(parserRawContent.maxDepthToBuildScript)
                    .onEnter(::notSkipValidDirectories)
                    .filter(::isBuildScript)
                    .map(::mapBuildScriptPathAndType)
                    .toSet()

                GradleProjectStructure(rootFolder.name, foundedScripts).also {
                    logger().i("Parser :: Project structure parsed with success!")
                    logger().i("Parser :: Project name -> ${it.rootProjectName}")
                    logger().i("Parser :: Total number of Gradle scripts found -> ${it.scripts.size}")
                }
            }
            else -> {
                logger().e("Error -> Can't parsed this project structure. $rootFolder is not a directory")
                throw MagicModulesError.CantParseProjectStructure
            }
        }

    private fun File.evaluateProjectType(): GradleModuleType =
        if (matchesBuildSrc()) GradleModuleType.BUILDSRC
        else readLines().asSequence()
            .mapNotNull(::mapScriptLine)
            .mapNotNull(::mapFoundModule)
            .firstOrNull() ?: GradleModuleType.ROOT_LEVEL

    private fun isBuildScript(file: File): Boolean = with(file) {
        path.endsWith("build.gradle") || path.endsWith("build.gradle.kts")
    }

    private fun File.matchesBuildSrc(): Boolean = path.contains("buildSrc")

    private fun mapScriptLine(line: String): GradleFoundModule? {
        val pluginFound = PLUGIN_LINE_REGEX.find(line)
        return when {
            pluginFound != null ->
                GradleFoundModule.ApplyPlugin(line.substring(pluginFound.range.last))
            parserRawContent.rawLibraryUsingApplyFrom.isEmpty() -> null
            else -> APPLY_FROM_LINE_REGEX.find(line)?.let { match ->
                GradleFoundModule.ApplyFrom(line.substring(match.range.last))
            }
        }
    }

    private fun mapFoundModule(module: GradleFoundModule): GradleModuleType? =
        with(parserRawContent) {
            when (module) {
                is GradleFoundModule.ApplyFrom -> when {
                    module.isAPlugin(rawJavaLibraryUsingApplyFrom) -> GradleModuleType.JAVA_LIBRARY
                    module.isAPlugin(rawLibraryUsingApplyFrom) -> GradleModuleType.LIBRARY
                    else -> null
                }
                is GradleFoundModule.ApplyPlugin -> when {
                    module.isAPlugin(rawApplicationPlugins) -> GradleModuleType.APPLICATION
                    module.isAPlugin(rawJavaLibraryPlugins) -> GradleModuleType.JAVA_LIBRARY
                    module.isAPlugin(rawLibraryPlugins) -> GradleModuleType.LIBRARY
                    else -> null
                }
            }
        }

    private fun GradleFoundModule.isAPlugin(plugins: List<String>) =
        plugins.any { plugin -> content.contains(plugin) }

    private fun notSkipValidDirectories(file: File) = with(file) {
        isHidden.not() && name != "build" && name != "src"
    }

    private fun mapBuildScriptPathAndType(file: File) =
        GradleBuildScript(file.path, file.evaluateProjectType())

    companion object {
        private val APPLY_FROM_LINE_REGEX = """^\s*apply\s*\(?from\s*[:=]\s*['"]?""".toRegex()
        private val PLUGIN_LINE_REGEX =
            """^\s*((apply\s*\(?\s*plugin)|(id\s*[('"])|(kotlin\s*\())""".toRegex()
    }
}