package de.danoeh.antennapod.model.feed;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class SmartPlaylist implements Serializable {
    private long id;
    private String name;
    private boolean autoRegenerate;
    private long nextPlaylistId;
    private String nextPlaylistName;
    private long generatedAt;
    private long createdAt;
    private long updatedAt;
    private List<SmartPlaylistRule> rules;
    private int episodeCount;

    public SmartPlaylist() {
        this.rules = new ArrayList<>();
        this.autoRegenerate = true;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isAutoRegenerate() {
        return autoRegenerate;
    }

    public void setAutoRegenerate(boolean autoRegenerate) {
        this.autoRegenerate = autoRegenerate;
    }

    public long getNextPlaylistId() {
        return nextPlaylistId;
    }

    public void setNextPlaylistId(long nextPlaylistId) {
        this.nextPlaylistId = nextPlaylistId;
    }

    public String getNextPlaylistName() {
        return nextPlaylistName;
    }

    public void setNextPlaylistName(String nextPlaylistName) {
        this.nextPlaylistName = nextPlaylistName;
    }

    public long getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(long generatedAt) {
        this.generatedAt = generatedAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<SmartPlaylistRule> getRules() {
        return rules;
    }

    public void setRules(List<SmartPlaylistRule> rules) {
        this.rules = rules != null ? rules : new ArrayList<>();
    }

    public int getEpisodeCount() {
        return episodeCount;
    }

    public void setEpisodeCount(int episodeCount) {
        this.episodeCount = episodeCount;
    }
}
