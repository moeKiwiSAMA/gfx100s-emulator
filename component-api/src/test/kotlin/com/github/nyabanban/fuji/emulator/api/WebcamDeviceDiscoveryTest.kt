package com.github.nyabanban.fuji.emulator.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebcamDeviceDiscoveryTest {

    @Test
    fun parsesModernWindowsDshowVideoDevicesAndDeduplicatesWithAlternativeName() {
        val sample = """
            [dshow @ 000001] "Integrated Webcam" (video)
            [dshow @ 000001]   Alternative name "@device_pnp_\\?\usb#vid_046d&pid_082d#1"
            [dshow @ 000001] "Logitech BRIO" (video)
            [dshow @ 000001]   Alternative name "@device_pnp_\\?\usb#vid_046d&pid_085e#a"
            [dshow @ 000001] "Logitech BRIO" (video)
            [dshow @ 000001]   Alternative name "@device_pnp_\\?\usb#vid_046d&pid_085e#b"
            [dshow @ 000001] "Microphone Array (Realtek)" (audio)
            [dshow @ 000001]   Alternative name "@device_cm_{33d9a762}"
        """.trimIndent()

        val devices = WebcamDeviceDiscovery.parseWindowsDshowDevices(sample)
        assertEquals(3, devices.size)
        assertEquals(WebcamDevice("Integrated Webcam", "Integrated Webcam"), devices[0])
        assertEquals("webcam:Integrated Webcam", devices[0].sourceRef)
        assertEquals(WebcamDevice("@device_pnp_\\\\?\\usb#vid_046d&pid_085e#a", "Logitech BRIO #1"), devices[1])
        assertEquals(WebcamDevice("@device_pnp_\\\\?\\usb#vid_046d&pid_085e#b", "Logitech BRIO #2"), devices[2])
    }

    @Test
    fun parsesLegacyWindowsDshowVideoSectionAndSkipsAudioSection() {
        val sample = """
            [dshow @ 000001] DirectShow video devices (some may be both video and audio devices)
            [dshow @ 000001]  "GPD Front Camera"
            [dshow @ 000001]     Alternative name "@device_pnp_gpd_1"
            [dshow @ 000001]  "OBS Virtual Camera"
            [dshow @ 000001] DirectShow audio devices
            [dshow @ 000001]  "Desktop Mic"
        """.trimIndent()

        val devices = WebcamDeviceDiscovery.parseWindowsDshowDevices(sample)
        assertEquals(
            listOf(
                WebcamDevice("GPD Front Camera", "GPD Front Camera"),
                WebcamDevice("OBS Virtual Camera", "OBS Virtual Camera"),
            ),
            devices,
        )
    }

    @Test
    fun parsesLinuxV4l2SysfsFilteringMetadataIndex1AndSortingNumerically() {
        val entries = listOf(
            WebcamDeviceDiscovery.LinuxVideoSysfsEntry("video10", "USB Capture", index = 0),
            WebcamDeviceDiscovery.LinuxVideoSysfsEntry("video1", "Integrated Camera", index = 1),
            WebcamDeviceDiscovery.LinuxVideoSysfsEntry("video0", "Integrated Camera", index = 0),
            WebcamDeviceDiscovery.LinuxVideoSysfsEntry("video2", "Missing Cam", index = 0, deviceExists = false),
        )
        val devices = WebcamDeviceDiscovery.parseLinuxSysfsEntries(entries)
        assertEquals(
            listOf(
                WebcamDevice("/dev/video0", "Integrated Camera (/dev/video0)"),
                WebcamDevice("/dev/video10", "USB Capture (/dev/video10)"),
            ),
            devices,
        )
    }

    @Test
    fun parsesMacAvFoundationVideoDevicesExcludingScreenCaptureAndAudio() {
        val sample = """
            [AVFoundation indev @ 0x1] AVFoundation video devices:
            [AVFoundation indev @ 0x1] [0] FaceTime HD Camera
            [AVFoundation indev @ 0x1] [1] Desk View Camera
            [AVFoundation indev @ 0x1] [2] Capture screen 0
            [AVFoundation indev @ 0x1] AVFoundation audio devices:
            [AVFoundation indev @ 0x1] [0] MacBook Pro Microphone
        """.trimIndent()
        val devices = WebcamDeviceDiscovery.parseMacAvFoundationDevices(sample)
        assertEquals(
            listOf(
                WebcamDevice("0", "[0] FaceTime HD Camera"),
                WebcamDevice("1", "[1] Desk View Camera"),
            ),
            devices,
        )
        assertTrue(devices.none { it.displayName.contains("Capture screen") })
    }
}
