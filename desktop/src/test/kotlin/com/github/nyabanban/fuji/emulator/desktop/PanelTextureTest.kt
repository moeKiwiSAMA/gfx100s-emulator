package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.*
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import kotlin.test.*

class PanelTextureTest {
    private fun pattern(content: String, attributes: String = "width='8' height='8' patternUnits='userSpaceOnUse'"): Element {
        val root = PanelResources.document(("<svg xmlns='http://www.w3.org/2000/svg'><defs><pattern id='grain' $attributes>" + content + "</pattern></defs><rect fill='url(#grain)'/></svg>").toByteArray())
        return root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "pattern").item(0) as Element
    }
    @Test fun originalAlphaTextureBlendsSourceOverWithoutOpaqueBackgroundOrPixelMutation() {
        val p = pattern("<rect x='1' y='1' width='1' height='1' fill='#ffffff' opacity='0.5'/><rect x='1' y='1' width='1' height='1' fill='#000000' opacity='0.5'/>")
        val pixels = SvgArtwork.texturePixels(p)
        assertEquals(64, pixels.size)
        assertTrue((pixels[9] ushr 24) in 191..192)
        assertTrue(((pixels[9] ushr 16) and 255) in 84..86)
        assertEquals(0, pixels[0]); assertEquals(0, pixels[10])
        assertContentEquals(pixels, SvgArtwork.texturePixels(p))
    }
    @Test fun fractionalMicrofacetsUseAreaCoverageAndPreserveTransparentNeighbours() {
        val p = pattern("<rect x='0.5' y='0.5' width='1' height='1' fill='#ffffff'/>")
        val pixels = SvgArtwork.texturePixels(p)
        listOf(0,1,8,9).forEach { assertEquals(64, pixels[it] ushr 24); assertEquals(0xffffff, pixels[it] and 0xffffff) }
        assertEquals(4, pixels.count { it != 0 })
    }
    @Test fun texturesRefuseRecursionExternalResourcesUnknownPrimitivesTransformsAndAllocationOverflow() {
        val rect = "<rect x='1' y='1' width='1' height='1' fill='#ffffff'/>"
        listOf("width='129' height='8' patternUnits='userSpaceOnUse'", "width='8' height='8' patternUnits='objectBoundingBox'", "width='8' height='8' patternUnits='userSpaceOnUse' patternTransform='scale(2)'").forEach { assertFails { pattern(rect,it) } }
        listOf("<rect x='7' y='1' width='2' height='1' fill='#ffffff'/>", "<rect x='1' y='1' width='1' height='1' fill='url(#grain)'/>", "<rect x='1' y='1' width='1' height='1' fill='#ffffff' opacity='NaN'/>", "<image href='https://example.com/texture.png'/>", "<g>$rect</g>", "<path d='M0 0' fill='#ffffff'/>", rect.repeat(1025)).forEach { assertFails { pattern(it) } }
    }
    @Test fun allSixAssetsUseOneContinuousCoordinateFieldPerMaterialWithoutSmallTiles() {
        PanelCatalog.load().forEach { panel ->
            val root = PanelCatalog.validateArtwork(panel)
            val all = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "pattern")
            val generated = (0 until all.length).map { all.item(it) as Element }.filter { it.hasAttribute("data-generator") }
            assertEquals(2, generated.size)
            val specs = generated.map { p ->
                assertEquals(1200 to 800, PanelResources.textureSize(p))
                assertEquals("original-continuous-material", p.getAttribute("data-design"))
                PanelResources.continuousSpec(p)
            }
            assertEquals(setOf("paint", "rubber"), specs.map { it.kind }.toSet())
            assertEquals(1, specs.map { it.seed }.toSet().size)
            val source = String(PanelResources.bytes(panel.svg))
            assertFalse(source.contains("fill=\"#b4b4b4\" opacity=\"0.095\""))
        }
    }
    @Test fun continuousMaterialsAreDeterministicBoundedAndSeedSensitive() {
        val a = ContinuousMaterial.pixels(ContinuousMaterial.Spec("paint", 1778994289))
        assertEquals(960000, a.size)
        assertContentEquals(a, ContinuousMaterial.pixels(ContinuousMaterial.Spec("paint", 1778994289)))
        val b = ContinuousMaterial.pixels(ContinuousMaterial.Spec("paint", 1778994290))
        assertTrue(a.indices.count { a[it] != b[it] } > a.size * .8)
        assertTrue(a.all { (it ushr 24) <= 80 && (it and 0xffffff) in setOf(0,0xffffff) })
        assertTrue(a.distinct().size > 40)
    }
    @Test fun formerTileOffsetsDoNotRepeatAndTheirBoundariesHaveNoSeamSpike() {
        val a = ContinuousMaterial.pixels(ContinuousMaterial.Spec("paint", 1778994289))
        fun signal(pixel: Int) = (pixel ushr 24) * (if ((pixel and 0xffffff) == 0) -1 else 1)
        for (offset in listOf(32,64,128)) {
            var equal = 0; var samples = 0
            for (y in 1 until 799 step 7) for (x in 1 until 1200-offset step 7) { if(a[y*1200+x] == a[y*1200+x+offset]) equal++; samples++ }
            assertTrue(equal < samples * .15)
        }
        var seam = 0L; var ordinary = 0L; var ns = 0; var no = 0
        for(y in 1 until 799 step 3) for(x in 1 until 1199) {
            val delta = kotlin.math.abs(signal(a[y*1200+x]) - signal(a[y*1200+x-1]))
            if(x % 32 == 0) { seam += delta; ns++ } else { ordinary += delta; no++ }
        }
        val ratio = seam.toDouble()/ns/(ordinary.toDouble()/no)
        assertTrue(ratio in .8..1.2)
    }
    @Test fun generatedMaterialsRefuseUnboundedDimensionsSeedsKindsAndUnexpectedChildren() {
        fun document(attrs: String, child: String = "") = "<svg xmlns='http://www.w3.org/2000/svg'><defs><pattern id='p' patternUnits='userSpaceOnUse' data-generator='continuous-grain-v1' $attrs>$child</pattern></defs><rect fill='url(#p)'/></svg>"
        val valid = "width='1200' height='800' data-kind='paint' data-seed='1778994289'"
        PanelResources.document(document(valid).toByteArray())
        listOf(valid.replace("1200","1201"),valid.replace("800","801"),valid.replace("paint","metal"),valid.replace("1778994289","-1"),valid.replace("1778994289","4294967296"),valid+" data-strength='999'",valid+" href='https://example.com/'").forEach { assertFails { PanelResources.document(document(it).toByteArray()) } }
        assertFails { PanelResources.document(document(valid,"<rect x='0' y='0' width='1' height='1' fill='#fff'/>").toByteArray()) }
        assertFails { ContinuousMaterial.Spec("rubber", Long.MAX_VALUE) }
    }
    @Test fun rubberAndPaintHaveDistinctContinuousMicrostructureWithoutReplacingLighting() {
        val paint = ContinuousMaterial.pixels(ContinuousMaterial.Spec("paint", 1778994289))
        val rubber = ContinuousMaterial.pixels(ContinuousMaterial.Spec("rubber", 1778994289))
        assertEquals(paint.size, rubber.size)
        assertTrue(paint.indices.count { paint[it] != rubber[it] } > paint.size * .7)
        assertTrue(rubber.any { (it ushr 24) == 0 })
        assertTrue(rubber.any { (it ushr 24) > 10 })
        assertTrue(rubber.none { (it ushr 24) == 255 })
    }

    @Test fun continuousReliefHasConnectedLocalFacetsRatherThanIndependentSaltAndPepper() {
        val p=ContinuousMaterial.pixels(ContinuousMaterial.Spec("paint",1778994289))
        fun s(i:Int)=(p[i] ushr 24).toDouble()*(if((p[i] and 0xffffff)==0)-1 else 1)
        var sa=0.0;var sb=0.0;var sc=0.0;var aa=0.0;var ab=0.0;var ac=0.0;var count=0
        for(y in 20 until 780 step 3)for(x in 20 until 1180 step 3){val i=y*1200+x;val a=s(i);val b=s(i+1);val c=s(i+97);sa+=a;sb+=b;sc+=c;aa+=a*a;ab+=a*b;ac+=a*c;count++}
        // Asymmetric black/white alpha has a nonzero mean. Correlation must use centered covariance.
        val variance=aa/count-(sa/count)*(sa/count)
        val adjacent=(ab/count-(sa/count)*(sb/count))/variance
        val unrelated=(ac/count-(sa/count)*(sc/count))/variance
        println("Material centered neighbour covariance: adjacent=$adjacent, offset97=$unrelated, variance=$variance")
        assertTrue(variance>0);assertTrue(adjacent>.05,"Adjacent microfacets need positive correlation: $adjacent")
        assertTrue(kotlin.math.abs(unrelated)<.08,"No long periodic correlation: $unrelated")
        assertTrue(adjacent>unrelated)
    }
    @Test fun materialStudyRendersTheActualGeneratorWithoutReferencePixels() {
        val dir=java.nio.file.Path.of("build/material-study");java.nio.file.Files.createDirectories(dir)
        for(kind in listOf("paint","rubber")) {
            val pixels=ContinuousMaterial.pixels(ContinuousMaterial.Spec(kind,1778994289))
            val image=java.awt.image.BufferedImage(1200,800,java.awt.image.BufferedImage.TYPE_INT_RGB)
            val base=if(kind=="paint")47 else 28
            val signal=DoubleArray(pixels.size) { i -> val a=pixels[i] ushr 24; val c=if((pixels[i] and 0xffffff)==0)0 else 255;val v=(c*a+base*(255-a)+127)/255;image.setRGB(i%1200,i/1200,(v shl 16)or(v shl 8)or v);v.toDouble() }
            javax.imageio.ImageIO.write(image,"png",dir.resolve(kind+"-actual-generator.png").toFile())
            assertTrue(signal.max()-signal.min()>10)
        }
    }
}
