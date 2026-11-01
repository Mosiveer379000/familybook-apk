package com.familybook.app;

/**
 * Sticker / GIF API settings.
 * Get a free GIPHY API key at https://developers.giphy.com (Create an App -> API) and paste it below.
 * Until a key is set, the sheet simply shows the gallery tile and emojis (no message, no toast).
 */
final class StickerConfig {
    private StickerConfig() { }

    static final String GIPHY_KEY = "Gcc7eydbOiqxjXjJTPrV0IvyPQUY5ipd";

    static boolean hasKey() {
        return GIPHY_KEY != null && !GIPHY_KEY.isEmpty() && !GIPHY_KEY.startsWith("PASTE_");
    }
}
