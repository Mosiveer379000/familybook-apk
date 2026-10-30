package com.familybook.app;

/**
 * Interests সেকশনের ৫টি ক্যাটাগরি: Music, TV shows, Movies, Games, Sports teams and athletes।
 * প্রতিটির আলাদা JSON (assets/interest_*.json) ও আলাদা info key।
 *   ডাটা: profiles.info.<key> = ["নাম", ...]
 */
public final class InterestKind {

    public static final int MUSIC = 0, TV = 1, MOVIES = 2, GAMES = 3, SPORTS = 4;

    public static final String[] LABEL = {"Music", "TV shows", "Movies", "Games", "Sports teams and athletes"};
    public static final String[] TITLE = {"Add music", "Add TV shows", "Add movies", "Add games", "Add sports teams and athletes"};
    public static final String[] HINT = {
            "Search songs or artists", "Search TV shows", "Search movies", "Search games", "Search clubs, teams or athletes"};
    public static final String[] EMPTY = {
            "No music added yet. Search above and tap to add.",
            "No TV shows added yet. Search above and tap to add.",
            "No movies added yet. Search above and tap to add.",
            "No games added yet. Search above and tap to add.",
            "Nothing added yet. Search above and tap to add."};
    public static final String[] SEL_HEAD = {"Your music", "Your TV shows", "Your movies", "Your games", "Your teams and athletes"};

    // আলাদা আলাদা JSON ফাইল + তার ভেতরের অ্যারের নাম
    public static final String[] FILE = {
            "interest_music.json", "interest_tv_shows.json", "interest_movies.json",
            "interest_games.json", "interest_sports.json"};
    public static final String[] ARRAY = {"music", "tv_shows", "movies", "games", "sports"};

    // profiles.info-এর key
    public static final String[] KEY = {
            "interest_music", "interest_tv_shows", "interest_movies", "interest_games", "interest_sports"};

    // কোন কোন ক্যাটাগরিতে অঞ্চল (Asia / Arab / USA / China) ফিল্টার থাকবে
    public static boolean hasRegions(int kind) { return kind == TV || kind == MOVIES; }

    public static final String[] REGIONS = {"All", "Asia", "Arab", "USA", "China"};

    public static final int MAX = 50;

    private InterestKind() { }
}
