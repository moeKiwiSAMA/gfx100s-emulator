package com.github.nyabanban.fuji.emulator.components.dram

import java.nio.file.Files
import java.nio.file.Path

/** Shared Gradle test paths; each invocation retains fresh native reports. */
internal object DramTestPaths {
 private val root=Path.of(requireNotNull(System.getProperty("componentProjectRoot")))
 private val module=root.resolve("components/soc-dram")
 private val output by lazy {val parent=module.resolve("build/qualification");Files.createDirectories(parent);Files.createTempDirectory(parent,"junit-")}
 fun value(name:String):String = when(name) {
  "fujiRepo"->root.toString()
  "studyOutput"->output.toString()
  "candidateJar"->module.resolve("build/libs/soc-dram-0.3.1.jar").toString()
  else->error("Unknown DRAM test path: "+name)
 }
 private val sourceIdentities=mutableMapOf<String,Map<String,Any>>()
 /** Daily preflight may be dirty; formal collection separately requires a committed source tree. */
 fun sourceIdentity(model:String):Map<String,Any> = sourceIdentities.getOrPut(model) {
  require(model in setOf("DramPairBank","DramCommandBank"))
  val paths=listOf("components/soc-dram/src/main/kotlin/com/github/nyabanban/fuji/emulator/components/dram/"+model+".kt",
   "components/soc-dram/src/test/kotlin/com/github/nyabanban/fuji/emulator/components/dram/"+(if(model=="DramPairBank")"DramPairNativeTest" else "DramCommandNativeTest")+".kt",
   "components/soc-dram/src/test/kotlin/com/github/nyabanban/fuji/emulator/components/dram/DramTestPaths.kt")
  fun git(vararg args:String):String {
   val process=ProcessBuilder(listOf("git","-c","core.longpaths=true","-C",root.toString())+args).redirectError(ProcessBuilder.Redirect.INHERIT).start()
   val output=process.inputStream.bufferedReader(Charsets.UTF_8).use {it.readText()}
   check(process.waitFor()==0) {"Git source provenance failed"};return output.trim()
  }
  val commit=git("rev-parse","HEAD");require(commit.matches(Regex("[0-9a-f]{40,64}")))
  require(paths.all {Files.isRegularFile(root.resolve(it))})
  git("ls-files","--error-unmatch","--",*paths.toTypedArray())
  mapOf("sourceCommit" to commit,"sourcePaths" to paths,"sourceDirty" to git("status","--porcelain","--untracked-files=all","--",*paths.toTypedArray()).isNotEmpty())
 }
}
