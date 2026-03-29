package de.danoeh.antennapod.model.feed;

import java.io.Serializable;

public class SmartPlaylistRule implements Serializable {
    private long id;
    private long playlistId;
    private int position;
    private String filterProperties; // reuses FeedItemFilter format: "unplayed,downloaded,..."
    private String feedIds;          // comma-separated feed IDs, empty = all feeds
    private String feedTags;         // comma-separated tag names, empty = any
    private int maxAgeDays;          // 0 = no age limit
    private int minDurationMs;       // 0 = no minimum
    private int maxDurationMs;       // 0 = no maximum
    private String mediaType;        // "audio", "video", or "" for any
    private int episodeLimit;        // 0 = no limit
    private String sortOrder;        // "NEWEST", "OLDEST", "SHORTEST", "LONGEST", "RANDOM"

    public SmartPlaylistRule() {
        this.filterProperties = "unplayed,downloaded";
        this.feedIds = "";
        this.feedTags = "";
        this.maxAgeDays = 0;
        this.minDurationMs = 0;
        this.maxDurationMs = 0;
        this.mediaType = "";
        this.episodeLimit = 1;
        this.sortOrder = "NEWEST";
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getPlaylistId() {
        return playlistId;
    }

    public void setPlaylistId(long playlistId) {
        this.playlistId = playlistId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getFilterProperties() {
        return filterProperties;
    }

    public void setFilterProperties(String filterProperties) {
        this.filterProperties = filterProperties != null ? filterProperties : "";
    }

    public String getFeedIds() {
        return feedIds;
    }

    public void setFeedIds(String feedIds) {
        this.feedIds = feedIds != null ? feedIds : "";
    }

    public String getFeedTags() {
        return feedTags;
    }

    public void setFeedTags(String feedTags) {
        this.feedTags = feedTags != null ? feedTags : "";
    }

    public int getMaxAgeDays() {
        return maxAgeDays;
    }

    public void setMaxAgeDays(int maxAgeDays) {
        this.maxAgeDays = maxAgeDays;
    }

    public int getMinDurationMs() {
        return minDurationMs;
    }

    public void setMinDurationMs(int minDurationMs) {
        this.minDurationMs = minDurationMs;
    }

    public int getMaxDurationMs() {
        return maxDurationMs;
    }

    public void setMaxDurationMs(int maxDurationMs) {
        this.maxDurationMs = maxDurationMs;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType != null ? mediaType : "";
    }

    public int getEpisodeLimit() {
        return episodeLimit;
    }

    public void setEpisodeLimit(int episodeLimit) {
        this.episodeLimit = episodeLimit;
    }

    public String getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(String sortOrder) {
        this.sortOrder = sortOrder != null ? sortOrder : "NEWEST";
    }
}
