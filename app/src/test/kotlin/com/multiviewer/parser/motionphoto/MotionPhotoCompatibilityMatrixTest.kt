package com.multiviewer.parser.motionphoto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotionPhotoCompatibilityMatrixTest {

    @Test
    fun `evaluates Samsung SEF motion photo correctly across platforms`() {
        val specs = MotionPhotoProfileSpecs(
            hasSamsungSef = true,
            sefHasMotionPhotoData = true,
            hasGoogleXmpV2Directory = false,
            hasGoogleXmpV1MicroVideo = false,
            hasAppleLivePhotoPair = false,
            hasAppleMpvdBox = false,
            hasEmbeddedVideo = true,
            videoCodec = "HEVC",
        )

        val report = evaluateMotionPhotoCompatibility(specs)
        assertEquals("삼성 SEF 레거시 모션포토", report.detectedProfile)

        val samsung = report.entries.find { it.platformName.contains("삼성 갤러리") }
        assertNotNull(samsung)
        assertEquals(PlatformSupportLevel.FULL, samsung.supportLevel)

        val google = report.entries.find { it.platformName.contains("구글 포토") }
        assertNotNull(google)
        // SEF only files fallback to still photo in Google Photos
        assertEquals(PlatformSupportLevel.STILL_ONLY, google.supportLevel)

        val apple = report.entries.find { it.platformName.contains("애플 사진") }
        assertNotNull(apple)
        assertEquals(PlatformSupportLevel.STILL_ONLY, apple.supportLevel)
    }

    @Test
    fun `evaluates dual Samsung SEF and Google XMP v2 motion photo as fully supported on both`() {
        val specs = MotionPhotoProfileSpecs(
            hasSamsungSef = true,
            sefHasMotionPhotoData = true,
            hasGoogleXmpV2Directory = true,
            hasGoogleXmpV1MicroVideo = false,
            hasAppleLivePhotoPair = false,
            hasAppleMpvdBox = false,
            hasEmbeddedVideo = true,
            videoCodec = "HEVC",
        )

        val report = evaluateMotionPhotoCompatibility(specs)
        assertTrue(report.detectedProfile.contains("삼성 SEF + 구글 XMP v2 하이브리드"))

        val samsung = report.entries.find { it.platformName.contains("삼성 갤러리") }
        assertNotNull(samsung)
        assertEquals(PlatformSupportLevel.FULL, samsung.supportLevel)

        val google = report.entries.find { it.platformName.contains("구글 포토") }
        assertNotNull(google)
        assertEquals(PlatformSupportLevel.FULL, google.supportLevel)

        val xiaomi = report.entries.find { it.platformName.contains("샤오미 갤러리") }
        assertNotNull(xiaomi)
        assertEquals(PlatformSupportLevel.FULL, xiaomi.supportLevel)
    }

    @Test
    fun `evaluates Apple Live Photo pair correctly`() {
        val specs = MotionPhotoProfileSpecs(
            hasSamsungSef = false,
            sefHasMotionPhotoData = false,
            hasGoogleXmpV2Directory = false,
            hasGoogleXmpV1MicroVideo = false,
            hasAppleLivePhotoPair = true,
            hasAppleMpvdBox = false,
            hasEmbeddedVideo = true,
            videoCodec = "HEVC",
        )

        val report = evaluateMotionPhotoCompatibility(specs)
        assertEquals("Apple Live Photo (Still + MOV 페어)", report.detectedProfile)

        val apple = report.entries.find { it.platformName.contains("애플 사진") }
        assertNotNull(apple)
        assertEquals(PlatformSupportLevel.FULL, apple.supportLevel)

        val google = report.entries.find { it.platformName.contains("구글 포토") }
        assertNotNull(google)
        assertEquals(PlatformSupportLevel.FULL, google.supportLevel)

        val web = report.entries.find { it.platformName.contains("웹 브라우저") }
        assertNotNull(web)
        assertEquals(PlatformSupportLevel.STILL_ONLY, web.supportLevel)
    }

    @Test
    fun `evaluates HEIC mpvd embedded motion photo correctly`() {
        val specs = MotionPhotoProfileSpecs(
            hasSamsungSef = false,
            sefHasMotionPhotoData = false,
            hasGoogleXmpV2Directory = false,
            hasGoogleXmpV1MicroVideo = false,
            hasAppleLivePhotoPair = false,
            hasAppleMpvdBox = true,
            hasEmbeddedVideo = true,
            videoCodec = "HEVC",
        )

        val report = evaluateMotionPhotoCompatibility(specs)
        assertEquals("HEIC mpvd 임베디드 모션포토", report.detectedProfile)

        val samsung = report.entries.find { it.platformName.contains("삼성 갤러리") }
        assertNotNull(samsung)
        assertEquals(PlatformSupportLevel.FULL, samsung.supportLevel)
    }
}
