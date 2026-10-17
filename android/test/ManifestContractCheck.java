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
        System.out.println("ManifestContractCheck ok");
    }
}
