package de.danoeh.antennapod.model.feed;

import androidx.annotation.Nullable;

public class Queue {
    private final long id;
    private final String name;

    public Queue(long id, @Nullable String name) {
        this.id = id;
        this.name = name;
    }

    public long getId() {
        return id;
    }

    @Nullable
    public String getName() {
        return name;
    }
}
