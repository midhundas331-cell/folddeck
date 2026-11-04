package io.folddeck.spike;

import java.nio.file.Files;
import java.nio.file.Path;

/** Release manifest must permit both Fold portrait and landscape postures. */
public class ManifestContractCheck {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        String manifest = Files.readString(Path.of("android/AndroidManifest.xml"));
        check(manifest.contains("android:screenOrientation=\"fullUser\""),
                "activity follows the user's portrait and landscape rotation");
        check(!manifest.contains("android:screenOrientation=\"userLandscape\""),
                "activity no longer forces landscape on the Fold");
        // Phone mic/camera keep serving the laptop while FoldDeck is hidden (API 34+ rules).
        for (String permission : new String[]{"FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MICROPHONE",
                "FOREGROUND_SERVICE_CAMERA", "POST_NOTIFICATIONS"}) {
            check(manifest.contains("android.permission." + permission + "\""), "declares " + permission);
        }
        check(manifest.matches("(?s).*<service[^>]*android:name=\".SharingService\"[^>]*"
                + "android:foregroundServiceType=\"microphone\\|camera\".*"), "sharing service is microphone|camera");
        check(manifest.contains("android:versionCode=\"29\"") && manifest.contains("android:versionName=\"0.15.0\""),
                "version 29 / 0.15.0");
        System.out.println("ManifestContractCheck ok");
    }
}
