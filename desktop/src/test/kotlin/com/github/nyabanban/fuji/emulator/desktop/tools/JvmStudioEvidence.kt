package com.github.nyabanban.fuji.emulator.desktop.tools

import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Qualification of actual studio widget/owner observations. No supplied passed flag is consumed. */
object JvmStudioEvidence {
    private object JvmEvidenceIo {
        fun read(path: Path): Map<String, Any?> = Files.newInputStream(path).use { input ->
            com.github.nyabanban.fuji.emulator.runtime.Formats.json.readValue(input,
                object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {})
        }
        fun hash(path: Path): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
    }
    @Suppress("UNCHECKED_CAST") private fun obj(value: Any?) = value as? Map<String, Any?> ?: emptyMap()
    private fun number(value: Any?) = (value as? Number)?.toLong()
    private fun strings(value: Any?) = (value as? List<*>)?.filterIsInstance<String>()?.toSet()
    val expectedInputStates: Map<String, Set<String>> = linkedMapOf(
        "mousePressed" to setOf("menu"), "mouseReleased" to emptySet(),
        "keyboardPressed" to setOf("up"), "keyboardReleased" to emptySet(),
        "powerHeld" to setOf("power"), "beforeViewSwitch" to setOf("power", "menu"),
        "viewSwitchReleased" to setOf("power"), "viewRestored" to setOf("power"),
        "beforeFocusLoss" to setOf("power", "menu"), "focusLossReleased" to setOf("power"),
        "debugLatchSurvivesViewSwitch" to setOf("power", "menu"), "releaseAll" to emptySet())
    private val displayPorts = mapOf("rear-lcd" to "display.main", "top-shoulder" to "display.shoulder", "rear-evf" to "display.evf")
    fun checks(panels: Map<String, Any?>, events: Map<String, Any?>): Map<String, Boolean> {
        val observed = obj(events["observations"])
        val displays = obj(panels["displays"])
        fun exact(name: String) = obj(observed[name]) == PanelCatalog.buttonKeys.associateWith { it in expectedInputStates.getValue(name) }
        return linkedMapOf(
            "studioSixFaceStagesAndConfigurationMainEntry" to (number(panels["actualStageCount"]) == 7L && number(panels["createdFaceStageCount"]) == 6L),
            "studioSixPhysicalFacesHaveIndependentWindows" to (strings(panels["viewIds"]) == PanelCatalog.viewIds && (panels["viewIds"] as? List<*>)?.size == 6),
            "studioAllSixDirectionsSimultaneouslyAttached" to (panels["layoutMode"] == "three-column-face-windows" && strings(panels["attachedViewIds"]) == PanelCatalog.viewIds),
            "studioSixtyEightPhysicalRegionsAndAllExplicitKeys" to (number(panels["controls"]) == 68L && strings(panels["buttonKeys"]) == PanelCatalog.buttonKeys),
            "studioDisplaysEmbeddedInCorrectDirections" to (displays.keys == displayPorts.keys && obj(displays["rear-lcd"])["viewId"] == "rear" && obj(displays["rear-evf"])["viewId"] == "rear" && obj(displays["top-shoulder"])["viewId"] == "top"),
            "studioEvfNeverAliasesMainOrShoulder" to displayPorts.all { (id, port) -> obj(displays[id])["port"] == port },
            "studioAbsentFirmwareFramesRemainAbsent" to (displayPorts.keys.all { obj(displays[it])["waiting"] == true && number(obj(displays[it])["sequence"]) == 0L }),
            "studioNoFirmwareMenuOrElectricalClaim" to (panels["firmwareWiringVerified"] == false && panels["firmwareMenuVerified"] == false),
            "studioActualOwnerInputObservationsComplete" to (observed.keys == expectedInputStates.keys && expectedInputStates.keys.all { obj(observed[it]).keys == PanelCatalog.buttonKeys }),
            "studioMousePressAndReleaseReachButtonPort" to (exact("mousePressed") && exact("mouseReleased")),
            "studioKeyboardPressAndReleaseReachButtonPort" to (exact("keyboardPressed") && exact("keyboardReleased")),
            "studioAttentionChangeReleasesTransientAndRetainsPower" to (exact("powerHeld") && exact("beforeViewSwitch") && exact("viewSwitchReleased") && exact("viewRestored")),
            "studioActualWindowFocusLossReleasesTransientAndRetainsPower" to (exact("beforeFocusLoss") && exact("focusLossReleased")),
            "studioExplicitDebugLatchSurvivesDirectionChange" to exact("debugLatchSurvivesViewSwitch"),
            "studioReleaseAllClearsEveryObservedKey" to exact("releaseAll"),
            "studioConfigurationMainEntryActuallyVisible" to (panels["configurationEntryVisible"] == true && panels["configEditorVisible"] == true)) + layoutChecks(panels)
    }
    val projectionLayers = linkedMapOf("front" to "lens-attached-art", "top" to "lens-attached-top-art",
        "ports-left" to "lens-attached-ports-left-art", "storage-right" to "lens-attached-storage-right-art",
        "battery-bottom" to "lens-attached-battery-bottom-art")
    fun projectionChecks(output: Path): Map<String, Boolean> {
        val root = runCatching { obj(JvmEvidenceIo.read(output.resolve("ui-lens-projections.json"))) }.getOrDefault(emptyMap())
        val captures = (root["captures"] as? List<*>)?.map(::obj).orEmpty()
        fun name(view: String, attached: Boolean): String {
            val state = if (attached) "attached" else "detached"
            return if (view == "front") "ui-lens-$state.png" else "ui-lens-$view-$state.png"
        }
        fun decoded(file: String) = runCatching { ImageIO.read(output.resolve(file).toFile())?.let { it.width >= 800 && it.height >= 500 } == true }.getOrDefault(false)
        val pairs = captures.groupBy { it["selectedView"] }
        fun changedInsideFace(view:String):Boolean = runCatching {
            val rows=pairs[view].orEmpty();if(rows.size!=2) return@runCatching false
            val b=box(obj(rows[0]["layoutBounds"])[view])?:return@runCatching false
            val other=box(obj(rows[1]["layoutBounds"])[view])?:return@runCatching false
            val workspace=box(rows[0]["workspaceBounds"])?:return@runCatching false
            if(b!=other) return@runCatching false
            val a=ImageIO.read(output.resolve(name(view,true)).toFile())?:return@runCatching false
            val z=ImageIO.read(output.resolve(name(view,false)).toFile())?:return@runCatching false
            if(a.width!=z.width || a.height!=z.height) return@runCatching false
            val x=kotlin.math.floor(b.x-workspace.x).toInt();val y=kotlin.math.floor(b.y-workspace.y).toInt()
            val w=kotlin.math.ceil(b.width).toInt();val h=kotlin.math.ceil(b.height).toInt()
            if(x<0||y<0||x+w>a.width||y+h>a.height) return@runCatching false
            (y until y+h).any { py -> (x until x+w).any { px->a.getRGB(px,py)!=z.getRGB(px,py) } }
        }.getOrDefault(false)
        return linkedMapOf(
            "studioFiveLensProjectionPairsUseActualOwnerAndSevenWindows" to (captures.size == 10 && pairs.keys == projectionLayers.keys && projectionLayers.keys.all { view ->
                val rows = pairs[view].orEmpty()
                rows.map { it["ownerMountAttached"] } == listOf(false, true) && rows.all { row ->
                    number(row["actualStageCount"]) == 7L && strings(row["attachedViewIds"]) == PanelCatalog.viewIds && row["layoutMode"] == "three-column-face-windows" &&
                        row["file"] == name(view, row["ownerMountAttached"] == true) && row["firmwareWiringVerified"] == false
                }
            }),
            "studioAllProjectionLayersFollowActualMountOwner" to (captures.size == 10 && captures.all { row ->
                row["ownerMountAttached"] is Boolean && obj(row["layers"]).keys == projectionLayers.values.toSet() &&
                    projectionLayers.values.all { obj(row["layers"])[it] == row["ownerMountAttached"] }
            }),
            "studioEveryProjectedLensCapActuallyChangesPixels" to projectionLayers.keys.all { view ->
                val a = name(view, true); val b = name(view, false)
                decoded(a) && decoded(b) && changedInsideFace(view)
            })
    }
    fun screenshotChecks(output: Path): Map<String, Boolean> {
        val names = PanelCatalog.viewIds.map { "ui-studio-$it.png" }.toSet()
        val actual = Files.list(output).use { files -> files.map { it.fileName.toString() }.filter { it.startsWith("ui-studio-") && it.endsWith(".png") }.toList().toSet() }
        fun image(name: String) = runCatching { ImageIO.read(output.resolve(name).toFile())?.let { it.width >= 800 && it.height >= 500 } == true }.getOrDefault(false)
        val captureRoot = runCatching { obj(JvmEvidenceIo.read(output.resolve("ui-studio-captures.json"))) }.getOrDefault(emptyMap())
        val captures = (captureRoot["captures"] as? List<*>)?.map(::obj).orEmpty()
        val previewRoot = runCatching { obj(JvmEvidenceIo.read(output.resolve("ui-preview.json"))) }.getOrDefault(emptyMap())
        val previews = (previewRoot["observations"] as? List<*>)?.map(::obj).orEmpty()
        return linkedMapOf(
            "studioAllSixActualDirectionScreenshotsDecode" to (names == actual && names.all(::image)),
            "studioDirectionCapturesUseSameSixWindowsAndMainEntry" to (captures.size == 6 && captures.map { it["selectedView"] }.toSet() == PanelCatalog.viewIds && captures.all { c ->
                number(c["actualStageCount"]) == 7L && strings(c["attachedViewIds"]) == PanelCatalog.viewIds && c["layoutMode"] == "three-column-face-windows" && c["file"] == "ui-studio-" + c["selectedView"] + ".png"
            }),
            "studioOpenConfigurationScreenshotDecodes" to image("ui-controls.png"),
            "studioMountPreviewFollowsActualOwnerParameterBothWays" to (previews.size == 2 && previews.map { it["ownerMountAttached"] } == listOf(false, true) && previews.all { p ->
                p["selectedView"] == "front" && number(p["actualStageCount"]) == 7L && obj(p["layers"])["lens-attached-art"] == p["ownerMountAttached"] && p["firmwareWiringVerified"] == false
            }),
            "studioAttachedAndDetachedProductPreviewsReallyDiffer" to (image("ui-lens-attached.png") && image("ui-lens-detached.png") && runCatching {
                JvmEvidenceIo.hash(output.resolve("ui-lens-attached.png")) != JvmEvidenceIo.hash(output.resolve("ui-lens-detached.png"))
            }.getOrDefault(false))) + projectionChecks(output) + workbenchCaptureChecks(output) + lifecycleChecks(output) + configurationColorChecks(output) + frontHitChecks(output)
    }
    private fun box(value: Any?): StudioLayout.Box? {
        val m = obj(value)
        fun d(key: String) = (m[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
        val x=d("x")?:return null; val y=d("y")?:return null; val w=d("width")?:return null; val h=d("height")?:return null
        return if(w>0 && h>0) StudioLayout.Box(x,y,w,h) else null
    }
    fun layoutChecks(state: Map<String, Any?>): Map<String, Boolean> {
        val rows = obj(state["layoutBounds"])
        val bounds = rows.mapValues { box(it.value) }
        val workspace = box(state["workspaceBounds"])
        val admitted = state["layoutMode"] == "three-column-face-windows" && strings(state["attachedViewIds"]) == PanelCatalog.viewIds && rows.keys == PanelCatalog.viewIds && bounds.values.all { it != null }
        val visible = admitted && workspace != null && rows.values.all { obj(it)["visible"] == true } && bounds.values.filterNotNull().all { b -> b.x >= workspace.x-1 && b.y >= workspace.y-1 && b.x+b.width <= workspace.x+workspace.width+1 && b.y+b.height <= workspace.y+workspace.height+1 } && bounds.values.filterNotNull().let { b -> b.indices.all { i -> (i+1 until b.size).none { j -> StudioLayout.overlap(b[i],b[j]) } } }
        fun at(id: String) = bounds.getValue(id)!!
        val configuration=box(state["configurationWindow"])
        val arrangement=admitted && workspace!=null && configuration!=null && run {
            val rear=at("rear");val front=at("front");val top=at("top");val bottom=at("battery-bottom");val left=at("ports-left");val right=at("storage-right")
            val unit=rear.width/720.0;val gap=10*unit
            fun near(a:Double,b:Double)=kotlin.math.abs(a-b)<2.0
            near(left.x,front.x)&&near(front.x,right.x)&&near(left.width,front.width)&&near(front.width,right.width)&&
                near(top.x,rear.x)&&near(rear.x,bottom.x)&&near(top.width,rear.width)&&near(rear.width,bottom.width)&&rear.width>front.width*2.2&&
                near(left.y+left.height+gap,front.y)&&near(front.y+front.height+gap,right.y)&&
                near(top.y+top.height+gap,rear.y)&&near(rear.y+rear.height+gap,bottom.y)&&
                near(left.x+left.width+gap,rear.x)&&near(rear.x+rear.width+gap,configuration.x)
        }
        val entry=admitted && configuration!=null && workspace!=null && obj(state["configurationWindow"])["visible"]==true && configuration.x>=at("rear").x+at("rear").width && kotlin.math.abs(configuration.y-at("top").y)<2 && kotlin.math.abs(configuration.y+configuration.height-at("battery-bottom").y-at("battery-bottom").height)<2 && configuration.width>=339 && configuration.x+configuration.width<=workspace.x+workspace.width+1 && configuration.y+configuration.height<=workspace.y+workspace.height+1 && bounds.values.filterNotNull().none { StudioLayout.overlap(it,configuration) }
        return linkedMapOf("studioSixFacesVisibleInsideWorkspaceWithoutOverlap" to visible,"studioThreeColumnsPreserveLeftFrontRightAndTopRearBottomOrder" to arrangement,"studioConfigurationMainEntryIsLongRightColumnAndUnobscuring" to (admitted && entry))
    }
    fun workbenchCaptureChecks(output: Path): Map<String,Boolean> {
        val root=runCatching { obj(JvmEvidenceIo.read(output.resolve("ui-workbench-layout.json"))) }.getOrDefault(emptyMap())
        val rows=(root["captures"] as? List<*>)?.map(::obj).orEmpty()
        val usable=rows.size==3 && rows.all { r-> val b=box(r["configurationWindow"]);b!=null && b.width>=339 && b.height>=269 }
        return mapOf("studioConfigurationMainEntryRetainsIndependentUsableSize" to usable,"studioAllSixFacesSurviveSidebarAndCompactWindow" to (rows.size==3 && rows.map { it["mode"] }.toSet()==setOf("normal","sidebar","compact") && rows.all { row ->
            number(row["actualStageCount"])==7L && layoutChecks(row).values.all { it } &&
                runCatching { ImageIO.read(output.resolve(row["file"].toString()).toFile())?.let { it.width>=800 && it.height>=500 }==true }.getOrDefault(false)
        }))
    }
    fun lifecycleChecks(output:Path):Map<String,Boolean> {
        val root=runCatching { obj(JvmEvidenceIo.read(output.resolve("ui-window-lifecycle.json"))) }.getOrDefault(emptyMap())
        val rows=(root["rows"] as? List<*>)?.map(::obj).orEmpty()
        val shutdown=runCatching { obj(JvmEvidenceIo.read(output.resolve("ui-shutdown.json"))) }.getOrDefault(emptyMap())
        val finals=obj(root["final"])
        val drag=runCatching { obj(JvmEvidenceIo.read(output.resolve("ui-window-drag.json"))) }.getOrDefault(emptyMap())
        val dragRows=(drag["rows"] as? List<*>)?.map(::obj).orEmpty()
        return linkedMapOf(
            "studioThinWindowsDragOnlyByDedicatedHandlesWithoutButtonMisfire" to (dragRows.size==6 && dragRows.map { it["viewId"] }.toSet()==PanelCatalog.viewIds && dragRows.all { r -> r["stageStyle"]=="UNDECORATED" && r["dragHandleOnly"]==true && (r["afterX"] as? Number)?.toDouble() != (r["beforeX"] as? Number)?.toDouble() && (r["afterY"] as? Number)?.toDouble() != (r["beforeY"] as? Number)?.toDouble() } && obj(drag["buttonsBefore"])==PanelCatalog.buttonKeys.associateWith { false } && obj(drag["buttonsAfter"])==PanelCatalog.buttonKeys.associateWith { false }),
            "studioAllSixWindowTogglesCloseReopenAndReuseStages" to (rows.size==6 && rows.map { it["viewId"] }.toSet()==PanelCatalog.viewIds && number(root["stageCount"])==6L && rows.all { r -> listOf("hiddenByToggle","duplicateHideStable","reopened","closeRequestSynced","sameStageIdentity","showingAfterReopen").all { r[it]==true } } && strings(finals["attachedViewIds"])==PanelCatalog.viewIds && obj(finals["windowToggleStates"]).keys==PanelCatalog.viewIds && obj(finals["windowToggleStates"]).values.all { it==true }),
            "studioHidingWindowReleasesTransientHostButtons" to (obj(root["beforeHideButtons"])==PanelCatalog.buttonKeys.associateWith { it=="menu" } && obj(root["afterHideButtons"])==PanelCatalog.buttonKeys.associateWith { false }),
            "studioHiddenAndReopenedNativeWindowCapturesDiffer" to runCatching { val a=output.resolve("ui-face-hidden.png");val b=output.resolve("ui-face-reopened.png");ImageIO.read(a.toFile())!=null && ImageIO.read(b.toFile())!=null && JvmEvidenceIo.hash(a)!=JvmEvidenceIo.hash(b) }.getOrDefault(false),
            "studioMainEntryCloseStopsEveryWindowAndReleasesAllKeys" to (shutdown["mainCloseRequestHandled"]==true && number(shutdown["showingStagesAfterClose"])==0L && obj(shutdown["buttonsAfterClose"])==PanelCatalog.buttonKeys.associateWith { false }))
    }
    fun configurationColorChecks(output:Path):Map<String,Boolean> {
        val root=runCatching { obj(JvmEvidenceIo.read(output.resolve("ui-configuration-colors.json"))) }.getOrDefault(emptyMap())
        val rows=(root["rows"] as? List<*>)?.map(::obj).orEmpty()
        return mapOf("studioActualConfigurationTextPaintsUseNeutralGrayInsteadOfDefaultBlue" to (rows.size>=10 && rows.all { r ->
            val c=(r["rgba"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble()?.takeIf(Double::isFinite) }.orEmpty()
            c.size==4 && c.all { it in 0.0..1.0 } && kotlin.math.abs(c[0]-c[1])<.00001 && kotlin.math.abs(c[1]-c[2])<.00001 && r["smoothing"]=="GRAY"
        }))
    }
    fun frontHitChecks(output:Path):Map<String,Boolean> {
        val r=runCatching { obj(JvmEvidenceIo.read(output.resolve("ui-front-hit.json"))) }.getOrDefault(emptyMap())
        val t=obj(r["transform"])
        fun d(key:String)=(t[key] as? Number)?.toDouble()
        val before=obj(r["buttonsBefore"]);val after=obj(r["buttonsAfter"])
        return mapOf("studioFrontReallyRotates90AndNativeClickHitsTransformedControl" to (
            d("rotationDegrees")==90.0 && d("width")?.let { kotlin.math.abs(it-576)<.000001 }==true && d("height")?.let { kotlin.math.abs(it-972)<.000001 }==true && d("x")?.let { kotlin.math.abs(it)<.000001 }==true && d("y")?.let { kotlin.math.abs(it)<.000001 }==true &&
                r["targetId"]=="front-lens-release" && r["actualInspectedControlId"]==r["targetId"] && (r["inverseError"] as? Number)?.toDouble()?.let { it<.000001 }==true &&
                before.keys==PanelCatalog.buttonKeys && before.values.all { it is Boolean } && after==before))
    }
    /** Code provenance is Git commit + paths, never a per-source digest or a frozen source copy. */
    val bindingSourcePaths = listOf(
        "component-api/src/main/kotlin/com/github/nyabanban/fuji/emulator/api/Hardware.kt",
        "components/buttons/src/main/kotlin/com/github/nyabanban/fuji/emulator/components/buttons/ButtonsFactory.kt",
        "components/battery/src/main/kotlin/com/github/nyabanban/fuji/emulator/components/battery/BatteryFactory.kt",
        "components/lens/src/main/kotlin/com/github/nyabanban/fuji/emulator/components/lens/LensFactory.kt",
        "components/mount/src/main/kotlin/com/github/nyabanban/fuji/emulator/components/mount/MountFactory.kt",
        "components/sdcard-reader/src/main/kotlin/com/github/nyabanban/fuji/emulator/components/sdcard/SdcardFactory.kt",
        "runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/OwnerLoop.kt")
    fun sourceProvenance(repo: Path): Map<String, Any> {
        val root=repo.toAbsolutePath().normalize()
        require(bindingSourcePaths.all { relative ->
            val file=root.resolve(relative)
            Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) && file.toRealPath().startsWith(root.toRealPath())
        }) { "Missing hardware binding source path" }
        fun git(vararg args:String):String {
            val process=ProcessBuilder(listOf("git","-c","core.longpaths=true","-C",root.toString())+args)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val output=process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            require(process.waitFor()==0) { "Hardware binding Git provenance failed" };return output.trim()
        }
        val commit=git("rev-parse","HEAD");require(commit.matches(Regex("[0-9a-f]{40,64}")))
        git("ls-files","--error-unmatch","--",*bindingSourcePaths.toTypedArray())
        return mapOf("sourceCommit" to commit, "sourcePaths" to bindingSourcePaths,
            "sourceDirty" to git("status","--porcelain","--untracked-files=all","--",*bindingSourcePaths.toTypedArray()).isNotEmpty())
    }
    fun references(repo: Path): Map<String, Boolean> {
        val registry = obj(JvmEvidenceIo.read(repo.resolve("docs/ui/gfx100s/sources.generated.json")))
        val sources = (registry["sources"] as? List<*>)?.map(::obj).orEmpty(); val byId = sources.associateBy { it["id"] }
        val provenance = runCatching { sourceProvenance(repo) }.getOrNull()
        val visual = obj(JvmEvidenceIo.read(repo.resolve("docs/ui/gfx100s/visual-reference.generated.json")))
        val shots = (visual["screenshots"] as? List<*>)?.map(::obj).orEmpty()
        return mapOf(
            "studioOfficialLayoutIdentitiesRemainEighteenAndModelScoped" to (number(registry["sourceCount"]) == 18L && sources.size == 18 && byId.size == 18 && sources.all { s ->
                s["evidenceClass"] == "SOURCE" && number(s["httpStatus"]) == 200L && (number(s["bytes"]) ?: 0) > 0 && (s["sha256"] as? String)?.matches(Regex("[0-9a-f]{64}")) == true &&
                    (s["url"] as? String)?.let { url -> val uri = java.net.URI(url); uri.scheme == "https" && (uri.host == "fujifilm-dsc.com" || uri.host.endsWith(".fujifilm-dsc.com") || uri.host.endsWith(".fujifilm-x.com") || uri.host.endsWith(".fujifilm.com")) } == true
            }),
            "studioEveryPhysicalLayoutBasisUsesAdmittedGfxSource" to PanelCatalog.load().flatMap { it.controls }.all { c -> c.basis.isNotEmpty() && c.basis.all { byId.containsKey(it) && byId[it]?.get("excluded") != true } },
            "studioHardwareBindingGitProvenanceAvailable" to (provenance != null),
            "studioObservedStyleScreenshotsIdentifiedAndNeverFirmwareEvidence" to (visual["modelLayoutCopied"] == false && visual["firmwarePixelsCopied"] == false && visual["evidenceClass"] == "SOURCE" && shots.size == 2 && shots.map { it["state"] }.toSet() == setOf("home", "menu") && shots.all { s ->
                (number(s["bytes"]) ?: 0) > 0 && (s["sha256"] as? String)?.matches(Regex("[0-9a-f]{64}")) == true
            }))
    }
}
