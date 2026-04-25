package org.sawiq.collins.paper.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages video playlists for screens on the server side.
 * Each screen can have one active playlist with multiple videos.
 */
public final class Playlist {

    // Screen name -> Playlist
    private static final ConcurrentHashMap<String, Playlist> PLAYLISTS = new ConcurrentHashMap<>();

    private final String screenName;
    private final List<PlaylistEntry> entries;
    private volatile int currentIndex;
    private volatile boolean enabled;
    private volatile boolean loop;

    /**
     * A single entry in the playlist.
     */
    public record PlaylistEntry(int index, String url, String title) {
        public PlaylistEntry(int index, String url) {
            this(index, url, extractTitle(url));
        }

        private static String extractTitle(String url) {
            if (url == null) return "Unknown";
            
            // Check for YouTube URLs
            if (isYouTubeUrl(url)) {
                String id = extractYouTubeId(url);
                return id != null ? "YouTube: " + id : "YouTube Video";
            }
            
            // Extract filename from URL
            int lastSlash = url.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < url.length() - 1) {
                String name = url.substring(lastSlash + 1);
                int query = name.indexOf('?');
                if (query > 0) name = name.substring(0, query);
                if (name.length() > 40) name = name.substring(0, 37) + "...";
                return name;
            }
            
            return "Video";
        }

        private static boolean isYouTubeUrl(String url) {
            if (url == null) return false;
            String lower = url.toLowerCase();
            return lower.contains("youtube.com") || lower.contains("youtu.be");
        }

        private static String extractYouTubeId(String url) {
            if (url == null) return null;
            
            // youtu.be/ID
            if (url.contains("youtu.be/")) {
                int start = url.indexOf("youtu.be/") + 9;
                int end = url.indexOf('?', start);
                if (end < 0) end = url.indexOf('&', start);
                if (end < 0) end = url.length();
                if (end > start) return url.substring(start, Math.min(end, start + 11));
            }
            
            // youtube.com/watch?v=ID
            if (url.contains("v=")) {
                int start = url.indexOf("v=") + 2;
                int end = url.indexOf('&', start);
                if (end < 0) end = url.length();
                if (end > start) return url.substring(start, Math.min(end, start + 11));
            }
            
            return null;
        }
    }

    private Playlist(String screenName) {
        this.screenName = screenName;
        this.entries = Collections.synchronizedList(new ArrayList<>());
        this.currentIndex = 0;
        this.enabled = true;
        this.loop = true;
    }

    // ===== Static API =====

    /**
     * Get or create playlist for a screen.
     */
    public static Playlist getOrCreate(String screenName) {
        return PLAYLISTS.computeIfAbsent(screenName.toLowerCase(), Playlist::new);
    }

    /**
     * Get playlist for a screen (may be null).
     */
    public static Playlist get(String screenName) {
        return PLAYLISTS.get(screenName.toLowerCase());
    }

    /**
     * Remove playlist for a screen.
     */
    public static void remove(String screenName) {
        PLAYLISTS.remove(screenName.toLowerCase());
    }

    /**
     * Get all screen names with playlists.
     */
    public static List<String> getScreenNames() {
        return new ArrayList<>(PLAYLISTS.keySet());
    }

    /**
     * Clear all playlists.
     */
    public static void clearAll() {
        PLAYLISTS.clear();
    }

    // ===== Instance methods =====

    /**
     * Add a video URL to the playlist.
     * Returns the assigned index.
     */
    public int add(String url) {
        int index = entries.size() + 1; // 1-based index
        entries.add(new PlaylistEntry(index, url));
        return index;
    }

    /**
     * Add a video URL with custom title.
     */
    public int add(String url, String title) {
        int index = entries.size() + 1;
        entries.add(new PlaylistEntry(index, url, title));
        return index;
    }

    /**
     * Insert a video at specific position (1-based).
     */
    public void insert(int position, String url) {
        if (position < 1) position = 1;
        if (position > entries.size() + 1) position = entries.size() + 1;
        boolean shiftCurrent = !entries.isEmpty() && (position - 1) <= currentIndex;

        entries.add(position - 1, new PlaylistEntry(position, url));
        if (shiftCurrent) {
            currentIndex++;
        }
        reindex();
    }

    /**
     * Remove entry by index (1-based).
     */
    public boolean remove(int index) {
        if (index < 1 || index > entries.size()) return false;
        int removedIndex = index - 1;
        entries.remove(removedIndex);
        reindex();

        if (entries.isEmpty()) {
            currentIndex = 0;
        } else if (removedIndex < currentIndex) {
            currentIndex--;
        } else if (currentIndex >= entries.size()) {
            currentIndex = entries.size() - 1;
        }

        return true;
    }

    /**
     * Clear the playlist.
     */
    public void clear() {
        entries.clear();
        currentIndex = 0;
    }

    /**
     * Get current entry.
     */
    public PlaylistEntry current() {
        if (entries.isEmpty() || currentIndex < 0 || currentIndex >= entries.size()) {
            return null;
        }
        return entries.get(currentIndex);
    }

    /**
     * Get entry by index (1-based).
     */
    public PlaylistEntry get(int index) {
        if (index < 1 || index > entries.size()) return null;
        return entries.get(index - 1);
    }

    /**
     * Move to next video.
     * Returns the new entry, or null if at end (and not looping).
     */
    public PlaylistEntry next() {
        if (entries.isEmpty()) return null;
        
        currentIndex++;
        if (currentIndex >= entries.size()) {
            if (loop) {
                currentIndex = 0;
            } else {
                currentIndex = entries.size() - 1;
                return null; // End of playlist
            }
        }
        
        return current();
    }

    /**
     * Move to previous video.
     */
    public PlaylistEntry previous() {
        if (entries.isEmpty()) return null;
        
        currentIndex--;
        if (currentIndex < 0) {
            currentIndex = loop ? entries.size() - 1 : 0;
        }
        
        return current();
    }

    /**
     * Jump to specific index (1-based).
     */
    public PlaylistEntry jumpTo(int index) {
        if (index < 1 || index > entries.size()) return null;
        currentIndex = index - 1;
        return current();
    }

    /**
     * Check if there's a next video.
     */
    public boolean hasNext() {
        if (entries.isEmpty()) return false;
        return loop || currentIndex < entries.size() - 1;
    }

    /**
     * Get all entries.
     */
    public List<PlaylistEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    /**
     * Get number of entries.
     */
    public int size() {
        return entries.size();
    }

    /**
     * Check if empty.
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Get current index (1-based for display).
     */
    public int getCurrentIndex() {
        return currentIndex + 1;
    }

    /**
     * Get screen name.
     */
    public String getScreenName() {
        return screenName;
    }

    /**
     * Check if playlist is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enable/disable playlist.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Check if playlist loops.
     */
    public boolean isLoop() {
        return loop;
    }

    /**
     * Set playlist loop mode.
     */
    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    private void reindex() {
        for (int i = 0; i < entries.size(); i++) {
            PlaylistEntry old = entries.get(i);
            entries.set(i, new PlaylistEntry(i + 1, old.url(), old.title()));
        }
    }
}
