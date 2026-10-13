package dev.mcwebclient.web.mc;

import com.mojang.blaze3d.audio.DeviceList;
import com.mojang.blaze3d.audio.Library;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import org.jspecify.annotations.Nullable;

/**
 * Audio is not implemented yet (Phase 7: OpenAL binding emulation on WebAudio). Until
 * then OpenAL looks absent: no devices, and initializing the library fails the way a
 * machine without a sound device does, so SoundEngine turns sounds off and carries on.
 */
final class AudioSubstitutions {
    private AudioSubstitutions() {
    }
}

@TargetClass(DeviceList.class)
final class Target_com_mojang_blaze3d_audio_DeviceList {
    @Substitute
    public static DeviceList query() {
        return DeviceList.EMPTY;
    }
}

@TargetClass(className = "com.mojang.blaze3d.audio.CallbackDeviceTracker")
final class Target_com_mojang_blaze3d_audio_CallbackDeviceTracker {
    @Substitute
    public static boolean isSupported() {
        return false;
    }
}

@TargetClass(Library.class)
final class Target_com_mojang_blaze3d_audio_Library {
    @Substitute
    public void init(@Nullable String preferredDevice, DeviceList currentDevices, boolean useHrtf) {
        throw new IllegalStateException("Audio is not available in the browser build yet");
    }
}
