package de.danoeh.antennapod.storage.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.DatabaseUtils;
import android.database.DefaultDatabaseErrorHandler;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import de.danoeh.antennapod.model.feed.FeedCounter;
import de.danoeh.antennapod.model.feed.FeedFunding;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.download.DownloadResult;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.model.feed.SmartPlaylist;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import de.danoeh.antennapod.storage.database.mapper.FeedItemFilterQuery;
import de.danoeh.antennapod.storage.database.mapper.FeedItemSortQuery;
import de.danoeh.antennapod.storage.database.mapper.SmartPlaylistRuleQuery;

import de.danoeh.antennapod.system.utils.ThreadUtils;
import org.apache.commons.io.FileUtils;

import static de.danoeh.antennapod.model.feed.FeedPreferences.SPEED_USE_GLOBAL;
import static de.danoeh.antennapod.model.feed.SortOrder.toCodeString;

/**
 * Implements methods for accessing the database
 */
public class PodDBAdapter {

    private static final String TAG = "PodDBAdapter";
    public static final String DATABASE_NAME = "Antennapod.db";
    // FORK: 3120001 converts Smart Queue tables written by the earlier fork, which named their
    // columns without the sp_ prefix. The bump is what makes the conversion run: a database
    // restored from one of those backups is stamped 3120000, so without a higher VERSION no
    // upgrade fires and the rename never happens.
    public static final int VERSION = 3120001;

    /**
     * FORK: how far through upstream's migration chain the code in this fork actually goes.
     *
     * <p>{@link #VERSION} was bumped to 3120000 to carry the Smart Queue tables, but upstream had
     * not reached that number — its own VERSION is still 3110000. The stamp therefore claims an
     * upstream schema level the database does not have, and once upstream ships a migration of
     * its own numbered at or below 3120000, every already-stamped device would skip it: the
     * chain in {@link DBUpgrader} is written as {@code if (oldVersion < N)}, and the stamp is
     * already >= N. Nothing would fail loudly — upstream's new columns would simply be absent.
     *
     * <p>So the upstream chain is driven by a separately recorded level (see
     * {@link #readUpstreamSchemaLevel}) rather than by the stamp. Note the level has to be
     * persisted rather than derived: deriving it would make the chain re-run on every subsequent
     * upgrade, and re-running an {@code ALTER TABLE ADD COLUMN} fails outright.
     *
     * <p><b>Raise this to upstream's VERSION whenever upstream is merged in</b>, in the same
     * commit as the merge. That is what makes the newly merged migrations run once, and only
     * once, on existing installs.
     */
    static final int UPSTREAM_SCHEMA_LEVEL = 3110000;

    /**
     * FORK: the stamp the fork's original migration wrote, and the upstream level a database
     * carrying it actually has.
     *
     * <p>These are historical facts and must never be edited — unlike {@link
     * #UPSTREAM_SCHEMA_LEVEL}, which moves with each upstream merge. They are what lets a device
     * that predates the {@link #TABLE_NAME_FORK_SCHEMA} bookkeeping be placed correctly on the
     * upstream chain. Deriving the fallback from the moving constant instead would defeat the
     * whole mechanism: the moment {@code VERSION} is bumped during a merge, those devices would
     * be mistaken for genuine upstream installs and would skip the very migration being added.
     */
    private static final int LEGACY_FORK_STAMP = 3120000;
    private static final int LEGACY_FORK_UPSTREAM_LEVEL = 3110000;

    /** FORK: fork-owned bookkeeping that must not collide with any upstream table name. */
    static final String TABLE_NAME_FORK_SCHEMA = "ForkSchema";
    private static final String KEY_FORK_SCHEMA_NAME = "name";
    private static final String KEY_FORK_SCHEMA_VALUE = "value";
    private static final String FORK_SCHEMA_UPSTREAM_LEVEL = "upstream_level";

    private static final String CREATE_TABLE_FORK_SCHEMA = "CREATE TABLE IF NOT EXISTS "
            + TABLE_NAME_FORK_SCHEMA + " (" + KEY_FORK_SCHEMA_NAME + " TEXT PRIMARY KEY,"
            + KEY_FORK_SCHEMA_VALUE + " INTEGER NOT NULL)";

    /**
     * Maximum number of arguments for IN-operator.
     */
    private static final int IN_OPERATOR_MAXIMUM = 800;

    // Key-constants
    public static final String KEY_ID = "id";
    public static final String KEY_TITLE = "title";
    public static final String KEY_CUSTOM_TITLE = "custom_title";
    public static final String KEY_LINK = "link";
    public static final String KEY_DESCRIPTION = "description";
    public static final String KEY_FILE_URL = "file_url";
    public static final String KEY_DOWNLOAD_URL = "download_url";
    public static final String KEY_PUBDATE = "pubDate";
    public static final String KEY_READ = "read";
    public static final String KEY_DURATION = "duration";
    public static final String KEY_POSITION = "position";
    public static final String KEY_SIZE = "filesize";
    public static final String KEY_MIME_TYPE = "mime_type";
    public static final String KEY_IMAGE_URL = "image_url";
    public static final String KEY_FEED = "feed";
    public static final String KEY_MEDIA = "media";
    public static final String KEY_DOWNLOAD_DATE = "downloaded";
    public static final String KEY_LAST_REFRESH_ATTEMPT = "downloaded";
    public static final String KEY_LASTUPDATE = "last_update";
    public static final String KEY_FEEDFILE = "feedfile";
    public static final String KEY_REASON = "reason";
    public static final String KEY_SUCCESSFUL = "successful";
    public static final String KEY_FEEDFILETYPE = "feedfile_type";
    public static final String KEY_COMPLETION_DATE = "completion_date";
    public static final String KEY_FEEDITEM = "feeditem";
    public static final String KEY_PAYMENT_LINK = "payment_link";
    public static final String KEY_START = "start";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_AUTHOR = "author";
    public static final String KEY_HAS_CHAPTERS = "has_simple_chapters";
    public static final String KEY_TYPE = "type";
    public static final String KEY_ITEM_IDENTIFIER = "item_identifier";
    public static final String KEY_FEED_IDENTIFIER = "feed_identifier";
    public static final String KEY_REASON_DETAILED = "reason_detailed";
    public static final String KEY_DOWNLOADSTATUS_TITLE = "title";
    public static final String KEY_AUTO_DOWNLOAD_ENABLED = "auto_download"; // Both tables use the same key
    public static final String KEY_KEEP_UPDATED = "keep_updated";
    public static final String KEY_AUTO_DELETE_ACTION = "auto_delete_action";
    public static final String KEY_FEED_VOLUME_ADAPTION = "feed_volume_adaption";
    public static final String KEY_PLAYED_DURATION = "played_duration";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_IS_PAGED = "is_paged";
    public static final String KEY_NEXT_PAGE_LINK = "next_page_link";
    public static final String KEY_HIDE = "hide";
    public static final String KEY_SORT_ORDER = "sort_order";
    public static final String KEY_LAST_UPDATE_FAILED = "last_update_failed";
    public static final String KEY_HAS_EMBEDDED_PICTURE = "has_embedded_picture";
    public static final String KEY_LAST_PLAYED_TIME_HISTORY = "playback_completion_date";
    public static final String KEY_LAST_PLAYED_TIME_STATISTICS = "last_played_time";
    public static final String KEY_INCLUDE_FILTER = "include_filter";
    public static final String KEY_EXCLUDE_FILTER = "exclude_filter";
    public static final String KEY_MINIMAL_DURATION_FILTER = "minimal_duration_filter";
    public static final String KEY_FEED_PLAYBACK_SPEED = "feed_playback_speed";
    public static final String KEY_FEED_SKIP_SILENCE = "feed_skip_silence";
    public static final String KEY_FEED_SKIP_INTRO = "feed_skip_intro";
    public static final String KEY_FEED_SKIP_ENDING = "feed_skip_ending";
    public static final String KEY_FEED_TAGS = "tags";
    public static final String KEY_EPISODE_NOTIFICATION = "episode_notification";
    public static final String KEY_NEW_EPISODES_ACTION = "new_episodes_action";
    public static final String KEY_PODCASTINDEX_CHAPTER_URL = "podcastindex_chapter_url";
    public static final String KEY_SOCIAL_INTERACT_URL = "social_interact_url";
    public static final String KEY_STATE = "state";
    public static final String KEY_PODCASTINDEX_TRANSCRIPT_URL = "podcastindex_transcript_url";
    public static final String KEY_PODCASTINDEX_TRANSCRIPT_TYPE = "podcastindex_transcript_type";

    // FORK: Smart Playlist column keys
    public static final String KEY_SMART_PLAYLIST_NAME = "sp_name";
    public static final String KEY_SMART_PLAYLIST_AUTO_REGENERATE = "sp_auto_regenerate";
    public static final String KEY_SMART_PLAYLIST_GENERATED_AT = "sp_generated_at";
    public static final String KEY_SMART_PLAYLIST_CREATED_AT = "sp_created_at";
    public static final String KEY_SMART_PLAYLIST_UPDATED_AT = "sp_updated_at";
    public static final String KEY_SMART_PLAYLIST_ID = "sp_playlist_id";
    public static final String KEY_SMART_PLAYLIST_POSITION = "sp_position";
    public static final String KEY_SMART_PLAYLIST_FILTER_PROPERTIES = "sp_filter_properties";
    public static final String KEY_SMART_PLAYLIST_FEED_IDS = "sp_feed_ids";
    public static final String KEY_SMART_PLAYLIST_FEED_TAGS = "sp_feed_tags";
    public static final String KEY_SMART_PLAYLIST_MAX_AGE_DAYS = "sp_max_age_days";
    public static final String KEY_SMART_PLAYLIST_MIN_DURATION_MS = "sp_min_duration_ms";
    public static final String KEY_SMART_PLAYLIST_MAX_DURATION_MS = "sp_max_duration_ms";
    public static final String KEY_SMART_PLAYLIST_MEDIA_TYPE = "sp_media_type";
    public static final String KEY_SMART_PLAYLIST_EPISODE_LIMIT = "sp_episode_limit";
    public static final String KEY_SMART_PLAYLIST_SORT_ORDER = "sp_sort_order";
    public static final String KEY_SMART_PLAYLIST_EPISODE_ID = "sp_episode_id";

    // Table names
    public static final String TABLE_NAME_FEEDS = "Feeds";
    public static final String TABLE_NAME_FEED_ITEMS = "FeedItems";
    public static final String TABLE_NAME_FEED_IMAGES = "FeedImages";
    public static final String TABLE_NAME_FEED_MEDIA = "FeedMedia";
    public static final String TABLE_NAME_DOWNLOAD_LOG = "DownloadLog";
    public static final String TABLE_NAME_QUEUE = "Queue";
    public static final String TABLE_NAME_SIMPLECHAPTERS = "SimpleChapters";
    public static final String TABLE_NAME_FAVORITES = "Favorites";
    // FORK: Smart Playlist tables
    public static final String TABLE_NAME_SMART_PLAYLISTS = "SmartPlaylists";
    public static final String TABLE_NAME_SMART_PLAYLIST_RULES = "SmartPlaylistRules";
    public static final String TABLE_NAME_SMART_PLAYLIST_EPISODES = "SmartPlaylistEpisodes";

    // SQL Statements for creating new tables
    private static final String TABLE_PRIMARY_KEY = KEY_ID
            + " INTEGER PRIMARY KEY AUTOINCREMENT ,";

    private static final String CREATE_TABLE_FEEDS = "CREATE TABLE " + TABLE_NAME_FEEDS + " ("
            + TABLE_PRIMARY_KEY + KEY_TITLE + " TEXT,"
            + KEY_CUSTOM_TITLE + " TEXT,"
            + KEY_FILE_URL + " TEXT,"
            + KEY_DOWNLOAD_URL + " TEXT,"
            + KEY_LAST_REFRESH_ATTEMPT + " INTEGER,"
            + KEY_LINK + " TEXT,"
            + KEY_DESCRIPTION + " TEXT,"
            + KEY_PAYMENT_LINK + " TEXT,"
            + KEY_LASTUPDATE + " TEXT,"
            + KEY_LANGUAGE + " TEXT,"
            + KEY_AUTHOR + " TEXT,"
            + KEY_IMAGE_URL + " TEXT,"
            + KEY_TYPE + " TEXT,"
            + KEY_FEED_IDENTIFIER + " TEXT,"
            + KEY_AUTO_DOWNLOAD_ENABLED + " INTEGER DEFAULT 1,"
            + KEY_USERNAME + " TEXT,"
            + KEY_PASSWORD + " TEXT,"
            + KEY_INCLUDE_FILTER + " TEXT DEFAULT '',"
            + KEY_EXCLUDE_FILTER + " TEXT DEFAULT '',"
            + KEY_MINIMAL_DURATION_FILTER + " INTEGER DEFAULT -1,"
            + KEY_KEEP_UPDATED + " INTEGER DEFAULT 1,"
            + KEY_IS_PAGED + " INTEGER DEFAULT 0,"
            + KEY_NEXT_PAGE_LINK + " TEXT,"
            + KEY_HIDE + " TEXT,"
            + KEY_SORT_ORDER + " TEXT,"
            + KEY_LAST_UPDATE_FAILED + " INTEGER DEFAULT 0,"
            + KEY_AUTO_DELETE_ACTION + " INTEGER DEFAULT 0,"
            + KEY_FEED_PLAYBACK_SPEED + " REAL DEFAULT " + SPEED_USE_GLOBAL + ","
            + KEY_FEED_SKIP_SILENCE + " INTEGER DEFAULT " + FeedPreferences.SkipSilence.GLOBAL.code + ","
            + KEY_FEED_VOLUME_ADAPTION + " INTEGER DEFAULT 0,"
            + KEY_FEED_TAGS + " TEXT,"
            + KEY_FEED_SKIP_INTRO + " INTEGER DEFAULT 0,"
            + KEY_FEED_SKIP_ENDING + " INTEGER DEFAULT 0,"
            + KEY_EPISODE_NOTIFICATION + " INTEGER DEFAULT 0,"
            + KEY_STATE + " INTEGER DEFAULT " + Feed.STATE_SUBSCRIBED + ","
            + KEY_NEW_EPISODES_ACTION + " INTEGER DEFAULT 0)";

    private static final String CREATE_TABLE_FEED_ITEMS = "CREATE TABLE "
            + TABLE_NAME_FEED_ITEMS + " (" + TABLE_PRIMARY_KEY
            + KEY_TITLE + " TEXT," + KEY_PUBDATE + " INTEGER,"
            + KEY_READ + " INTEGER," + KEY_LINK + " TEXT,"
            + KEY_DESCRIPTION + " TEXT," + KEY_PAYMENT_LINK + " TEXT,"
            + KEY_MEDIA + " INTEGER," + KEY_FEED + " INTEGER,"
            + KEY_HAS_CHAPTERS + " INTEGER," + KEY_ITEM_IDENTIFIER + " TEXT,"
            + KEY_IMAGE_URL + " TEXT,"
            + KEY_AUTO_DOWNLOAD_ENABLED + " INTEGER,"
            + KEY_PODCASTINDEX_CHAPTER_URL + " TEXT,"
            + KEY_PODCASTINDEX_TRANSCRIPT_TYPE + " TEXT,"
            + KEY_PODCASTINDEX_TRANSCRIPT_URL + " TEXT,"
            + KEY_SOCIAL_INTERACT_URL + " TEXT)";

    private static final String CREATE_TABLE_FEED_MEDIA = "CREATE TABLE "
            + TABLE_NAME_FEED_MEDIA + " (" + TABLE_PRIMARY_KEY + KEY_DURATION
            + " INTEGER," + KEY_FILE_URL + " TEXT," + KEY_DOWNLOAD_URL
            + " TEXT," + KEY_DOWNLOAD_DATE + " INTEGER," + KEY_POSITION
            + " INTEGER," + KEY_SIZE + " INTEGER," + KEY_MIME_TYPE + " TEXT,"
            + KEY_LAST_PLAYED_TIME_HISTORY + " INTEGER,"
            + KEY_FEEDITEM + " INTEGER,"
            + KEY_PLAYED_DURATION + " INTEGER,"
            + KEY_HAS_EMBEDDED_PICTURE + " INTEGER,"
            + KEY_LAST_PLAYED_TIME_STATISTICS + " INTEGER" + ")";

    private static final String CREATE_TABLE_DOWNLOAD_LOG = "CREATE TABLE "
            + TABLE_NAME_DOWNLOAD_LOG + " (" + TABLE_PRIMARY_KEY + KEY_FEEDFILE
            + " INTEGER," + KEY_FEEDFILETYPE + " INTEGER," + KEY_REASON
            + " INTEGER," + KEY_SUCCESSFUL + " INTEGER," + KEY_COMPLETION_DATE
            + " INTEGER," + KEY_REASON_DETAILED + " TEXT,"
            + KEY_DOWNLOADSTATUS_TITLE + " TEXT)";

    private static final String CREATE_TABLE_QUEUE = "CREATE TABLE "
            + TABLE_NAME_QUEUE + "(" + KEY_ID + " INTEGER PRIMARY KEY,"
            + KEY_FEEDITEM + " INTEGER," + KEY_FEED + " INTEGER)";

    private static final String CREATE_TABLE_SIMPLECHAPTERS = "CREATE TABLE "
            + TABLE_NAME_SIMPLECHAPTERS + " (" + TABLE_PRIMARY_KEY + KEY_TITLE
            + " TEXT," + KEY_START + " INTEGER," + KEY_FEEDITEM + " INTEGER,"
            + KEY_LINK + " TEXT," + KEY_IMAGE_URL + " TEXT)";

    // SQL Statements for creating indexes
    static final String CREATE_INDEX_FEEDITEMS_FEED = "CREATE INDEX "
            + TABLE_NAME_FEED_ITEMS + "_" + KEY_FEED + " ON " + TABLE_NAME_FEED_ITEMS + " ("
            + KEY_FEED + ")";

    static final String CREATE_INDEX_FEEDITEMS_PUBDATE = "CREATE INDEX "
            + TABLE_NAME_FEED_ITEMS + "_" + KEY_PUBDATE + " ON " + TABLE_NAME_FEED_ITEMS + " ("
            + KEY_PUBDATE + ")";

    static final String CREATE_INDEX_FEEDITEMS_READ = "CREATE INDEX "
            + TABLE_NAME_FEED_ITEMS + "_" + KEY_READ + " ON " + TABLE_NAME_FEED_ITEMS + " ("
            + KEY_READ + ")";

    static final String CREATE_INDEX_QUEUE_FEEDITEM = "CREATE INDEX "
            + TABLE_NAME_QUEUE + "_" + KEY_FEEDITEM + " ON " + TABLE_NAME_QUEUE + " ("
            + KEY_FEEDITEM + ")";

    static final String CREATE_INDEX_FEEDMEDIA_FEEDITEM = "CREATE INDEX "
            + TABLE_NAME_FEED_MEDIA + "_" + KEY_FEEDITEM + " ON " + TABLE_NAME_FEED_MEDIA + " ("
            + KEY_FEEDITEM + ")";

    static final String CREATE_INDEX_SIMPLECHAPTERS_FEEDITEM = "CREATE INDEX "
            + TABLE_NAME_SIMPLECHAPTERS + "_" + KEY_FEEDITEM + " ON " + TABLE_NAME_SIMPLECHAPTERS + " ("
            + KEY_FEEDITEM + ")";

    static final String CREATE_TABLE_FAVORITES = "CREATE TABLE "
            + TABLE_NAME_FAVORITES + "(" + KEY_ID + " INTEGER PRIMARY KEY,"
            + KEY_FEEDITEM + " INTEGER," + KEY_FEED + " INTEGER)";

    // FORK: Smart Playlist CREATE TABLE statements
    static final String CREATE_TABLE_SMART_PLAYLISTS = "CREATE TABLE IF NOT EXISTS "
            + TABLE_NAME_SMART_PLAYLISTS + " (" + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + KEY_SMART_PLAYLIST_NAME + " TEXT NOT NULL,"
            + KEY_SMART_PLAYLIST_AUTO_REGENERATE + " INTEGER DEFAULT 1,"
            + KEY_SMART_PLAYLIST_GENERATED_AT + " INTEGER DEFAULT 0,"
            + KEY_SMART_PLAYLIST_CREATED_AT + " INTEGER NOT NULL,"
            + KEY_SMART_PLAYLIST_UPDATED_AT + " INTEGER NOT NULL)";

    static final String CREATE_TABLE_SMART_PLAYLIST_RULES = "CREATE TABLE IF NOT EXISTS "
            + TABLE_NAME_SMART_PLAYLIST_RULES + " (" + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + KEY_SMART_PLAYLIST_ID + " INTEGER REFERENCES " + TABLE_NAME_SMART_PLAYLISTS
            + "(" + KEY_ID + ") ON DELETE CASCADE,"
            + KEY_SMART_PLAYLIST_POSITION + " INTEGER DEFAULT 0,"
            + KEY_SMART_PLAYLIST_FILTER_PROPERTIES + " TEXT DEFAULT '',"
            + KEY_SMART_PLAYLIST_FEED_IDS + " TEXT DEFAULT '',"
            + KEY_SMART_PLAYLIST_FEED_TAGS + " TEXT DEFAULT '',"
            + KEY_SMART_PLAYLIST_MAX_AGE_DAYS + " INTEGER DEFAULT 0,"
            + KEY_SMART_PLAYLIST_MIN_DURATION_MS + " INTEGER DEFAULT 0,"
            + KEY_SMART_PLAYLIST_MAX_DURATION_MS + " INTEGER DEFAULT 0,"
            + KEY_SMART_PLAYLIST_MEDIA_TYPE + " TEXT DEFAULT '',"
            + KEY_SMART_PLAYLIST_EPISODE_LIMIT + " INTEGER DEFAULT 0,"
            + KEY_SMART_PLAYLIST_SORT_ORDER + " TEXT DEFAULT 'NEWEST')";

    static final String CREATE_TABLE_SMART_PLAYLIST_EPISODES = "CREATE TABLE IF NOT EXISTS "
            + TABLE_NAME_SMART_PLAYLIST_EPISODES + " (" + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + KEY_SMART_PLAYLIST_ID + " INTEGER REFERENCES " + TABLE_NAME_SMART_PLAYLISTS
            + "(" + KEY_ID + ") ON DELETE CASCADE,"
            + KEY_SMART_PLAYLIST_EPISODE_ID + " INTEGER REFERENCES " + TABLE_NAME_FEED_ITEMS + "(" + KEY_ID + "),"
            + KEY_SMART_PLAYLIST_POSITION + " INTEGER DEFAULT 0)";

    static final String CREATE_INDEX_SMART_PLAYLIST_EPISODES_PLAYLIST =
            "CREATE INDEX IF NOT EXISTS " + TABLE_NAME_SMART_PLAYLIST_EPISODES + "_playlist ON "
            + TABLE_NAME_SMART_PLAYLIST_EPISODES + " (" + KEY_SMART_PLAYLIST_ID + ")";

    static final String CREATE_INDEX_SMART_PLAYLIST_RULES_PLAYLIST =
            "CREATE INDEX IF NOT EXISTS " + TABLE_NAME_SMART_PLAYLIST_RULES + "_playlist ON "
            + TABLE_NAME_SMART_PLAYLIST_RULES + " (" + KEY_SMART_PLAYLIST_ID + ")";

    /**
     * All the tables in the database.
     *
     * <p>Drives {@link #deleteDatabase()}, which clears user content. FORK: TABLE_NAME_FORK_SCHEMA
     * is deliberately absent — it records how far the upstream migration chain has been applied,
     * which is a property of the schema rather than of the user's data, and wiping content must
     * not make the next upgrade replay migrations that have already run.
     */
    private static final String[] ALL_TABLES = {
            TABLE_NAME_FEEDS,
            TABLE_NAME_FEED_ITEMS,
            TABLE_NAME_FEED_MEDIA,
            TABLE_NAME_DOWNLOAD_LOG,
            TABLE_NAME_QUEUE,
            TABLE_NAME_SIMPLECHAPTERS,
            TABLE_NAME_FAVORITES,
            // FORK: Smart Playlist tables
            TABLE_NAME_SMART_PLAYLISTS,
            TABLE_NAME_SMART_PLAYLIST_RULES,
            TABLE_NAME_SMART_PLAYLIST_EPISODES
    };

    public static final String SELECT_KEY_ITEM_ID = "item_id";
    public static final String SELECT_KEY_MEDIA_ID = "media_id";
    public static final String SELECT_KEY_FEED_ID = "feed_id";
    public static final String SELECT_KEY_IS_FAVORITE = "is_favorite";
    public static final String SELECT_KEY_IS_IN_QUEUE = "is_in_queue";

    private static final String KEYS_FEED_ITEM_WITHOUT_DESCRIPTION =
            TABLE_NAME_FEED_ITEMS + "." + KEY_ID + " AS " + SELECT_KEY_ITEM_ID + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_TITLE + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_PUBDATE + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_READ + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_LINK + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_PAYMENT_LINK + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_MEDIA + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_FEED + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_HAS_CHAPTERS + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_ITEM_IDENTIFIER + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_IMAGE_URL + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_AUTO_DOWNLOAD_ENABLED + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_PODCASTINDEX_CHAPTER_URL + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_SOCIAL_INTERACT_URL + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_PODCASTINDEX_TRANSCRIPT_TYPE + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_PODCASTINDEX_TRANSCRIPT_URL + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_ID + " IN (SELECT " + TABLE_NAME_FAVORITES + "." + KEY_FEEDITEM
            + " FROM " + TABLE_NAME_FAVORITES + ") AS " + SELECT_KEY_IS_FAVORITE + ", "
            + TABLE_NAME_FEED_ITEMS + "." + KEY_ID + " IN (SELECT " + TABLE_NAME_QUEUE + "." + KEY_FEEDITEM
            + " FROM " + TABLE_NAME_QUEUE + ") AS " + SELECT_KEY_IS_IN_QUEUE;

    private static final String KEYS_FEED_MEDIA =
            TABLE_NAME_FEED_MEDIA + "." + KEY_ID + " AS " + SELECT_KEY_MEDIA_ID + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_DURATION + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_FILE_URL + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_DOWNLOAD_URL + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_DOWNLOAD_DATE + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_POSITION + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_SIZE + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_MIME_TYPE + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_LAST_PLAYED_TIME_HISTORY + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_FEEDITEM + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_PLAYED_DURATION + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_HAS_EMBEDDED_PICTURE + ", "
            + TABLE_NAME_FEED_MEDIA + "." + KEY_LAST_PLAYED_TIME_STATISTICS;

    private static final String KEYS_FEED =
            TABLE_NAME_FEEDS + "." + KEY_ID + " AS " + SELECT_KEY_FEED_ID + ", "
            + TABLE_NAME_FEEDS + "." + KEY_TITLE + ", "
            + TABLE_NAME_FEEDS + "." + KEY_CUSTOM_TITLE + ", "
            + TABLE_NAME_FEEDS + "." + KEY_FILE_URL + ", "
            + TABLE_NAME_FEEDS + "." + KEY_DOWNLOAD_URL + ", "
            + TABLE_NAME_FEEDS + "." + KEY_LAST_REFRESH_ATTEMPT + ", "
            + TABLE_NAME_FEEDS + "." + KEY_LINK + ", "
            + TABLE_NAME_FEEDS + "." + KEY_DESCRIPTION + ", "
            + TABLE_NAME_FEEDS + "." + KEY_PAYMENT_LINK + ", "
            + TABLE_NAME_FEEDS + "." + KEY_LASTUPDATE + ", "
            + TABLE_NAME_FEEDS + "." + KEY_LANGUAGE + ", "
            + TABLE_NAME_FEEDS + "." + KEY_AUTHOR + ", "
            + TABLE_NAME_FEEDS + "." + KEY_IMAGE_URL + ", "
            + TABLE_NAME_FEEDS + "." + KEY_TYPE + ", "
            + TABLE_NAME_FEEDS + "." + KEY_FEED_IDENTIFIER + ", "
            + TABLE_NAME_FEEDS + "." + KEY_IS_PAGED + ", "
            + TABLE_NAME_FEEDS + "." + KEY_NEXT_PAGE_LINK + ", "
            + TABLE_NAME_FEEDS + "." + KEY_LAST_UPDATE_FAILED + ", "
            + TABLE_NAME_FEEDS + "." + KEY_AUTO_DOWNLOAD_ENABLED + ", "
            + TABLE_NAME_FEEDS + "." + KEY_KEEP_UPDATED + ", "
            + TABLE_NAME_FEEDS + "." + KEY_USERNAME + ", "
            + TABLE_NAME_FEEDS + "." + KEY_PASSWORD + ", "
            + TABLE_NAME_FEEDS + "." + KEY_HIDE + ", "
            + TABLE_NAME_FEEDS + "." + KEY_SORT_ORDER + ", "
            + TABLE_NAME_FEEDS + "." + KEY_AUTO_DELETE_ACTION + ", "
            + TABLE_NAME_FEEDS + "." + KEY_FEED_VOLUME_ADAPTION + ", "
            + TABLE_NAME_FEEDS + "." + KEY_INCLUDE_FILTER + ", "
            + TABLE_NAME_FEEDS + "." + KEY_EXCLUDE_FILTER + ", "
            + TABLE_NAME_FEEDS + "." + KEY_MINIMAL_DURATION_FILTER + ", "
            + TABLE_NAME_FEEDS + "." + KEY_FEED_PLAYBACK_SPEED + ", "
            + TABLE_NAME_FEEDS + "." + KEY_FEED_SKIP_SILENCE + ", "
            + TABLE_NAME_FEEDS + "." + KEY_FEED_TAGS + ", "
            + TABLE_NAME_FEEDS + "." + KEY_FEED_SKIP_INTRO + ", "
            + TABLE_NAME_FEEDS + "." + KEY_FEED_SKIP_ENDING + ", "
            + TABLE_NAME_FEEDS + "." + KEY_EPISODE_NOTIFICATION + ", "
            + TABLE_NAME_FEEDS + "." + KEY_STATE + ", "
            + TABLE_NAME_FEEDS + "." + KEY_NEW_EPISODES_ACTION;

    private static final String JOIN_FEED_ITEM_AND_MEDIA = " LEFT JOIN " + TABLE_NAME_FEED_MEDIA
            + " ON " + TABLE_NAME_FEED_ITEMS + "." + KEY_ID + "=" + TABLE_NAME_FEED_MEDIA + "." + KEY_FEEDITEM + " ";

    private static final String SELECT_FEED_ITEMS_AND_MEDIA_WITH_DESCRIPTION =
            "SELECT " + KEYS_FEED_ITEM_WITHOUT_DESCRIPTION + ", " + KEYS_FEED_MEDIA + ", "
                    + TABLE_NAME_FEED_ITEMS + "." + KEY_DESCRIPTION
            + " FROM " + TABLE_NAME_FEED_ITEMS
            + JOIN_FEED_ITEM_AND_MEDIA;
    private static final String SELECT_FEED_ITEMS_AND_MEDIA =
            "SELECT " + KEYS_FEED_ITEM_WITHOUT_DESCRIPTION + ", " + KEYS_FEED_MEDIA
            + " FROM " + TABLE_NAME_FEED_ITEMS
            + JOIN_FEED_ITEM_AND_MEDIA;
    private static final String SELECT_WHERE_FEED_IS_SUBSCRIBED = TABLE_NAME_FEED_ITEMS + "." + KEY_FEED
            + " IN (SELECT " + KEY_ID + " FROM " + TABLE_NAME_FEEDS
            + " WHERE " + KEY_STATE + "=" + Feed.STATE_SUBSCRIBED + ")";

    private static Context context;
    private static PodDBAdapter instance;

    private final SQLiteDatabase db;
    private final PodDBHelper dbHelper;

    public static void init(Context context) {
        PodDBAdapter.context = context.getApplicationContext();
    }

    public static synchronized PodDBAdapter getInstance() {
        if (instance == null) {
            instance = new PodDBAdapter();
        }
        return instance;
    }

    private PodDBAdapter() {
        dbHelper = new PodDBHelper(PodDBAdapter.context, DATABASE_NAME, null);
        db = openDb();
    }

    private SQLiteDatabase openDb() {
        SQLiteDatabase newDb;
        try {
            newDb = dbHelper.getWritableDatabase();
        } catch (SQLException ex) {
            Log.e(TAG, Log.getStackTraceString(ex));
            newDb = dbHelper.getReadableDatabase();
        }
        return newDb;
    }

    public synchronized PodDBAdapter open() {
        ThreadUtils.assertNotMainThread();
        // do nothing
        return this;
    }

    public synchronized void close() {
        // do nothing
    }

    /**
     * <p>Resets all database connections to ensure new database connections for
     * the next test case. Call method only for unit tests.</p>
     *
     * <p>That's a workaround for a Robolectric issue in ShadowSQLiteConnection
     * that leads to an error <tt>IllegalStateException: Illegal connection
     * pointer</tt> if several threads try to use the same database connection.
     * For more information see
     * <a href="https://github.com/robolectric/robolectric/issues/1890">robolectric/robolectric#1890</a>.</p>
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public static void tearDownTests() {
        getInstance().dbHelper.close();
        instance = null;
    }

    public void walCheckpoint() {
        if (db == null || !db.isOpen() || !db.isWriteAheadLoggingEnabled()) {
            return;
        }
        try (Cursor cursor = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
            cursor.moveToFirst();
            Log.d(TAG, "WAL checkpoint result: " + DatabaseUtils.dumpCurrentRowToString(cursor));
        } catch (SQLiteException e) {
            Log.e(TAG, "wal_checkpoint PRAGMA failed", e);
        }
    }

    public static boolean deleteDatabase() {
        PodDBAdapter adapter = getInstance();
        adapter.open();
        try {
            for (String tableName : ALL_TABLES) {
                adapter.db.delete(tableName, "1", null);
            }
            return true;
        } finally {
            adapter.close();
        }
    }

    /**
     * Inserts or updates a feed entry
     *
     * @return the id of the entry
     */
    private long setFeed(Feed feed) {
        ContentValues values = new ContentValues();
        values.put(KEY_TITLE, feed.getFeedTitle());
        values.put(KEY_LINK, feed.getLink());
        values.put(KEY_DESCRIPTION, feed.getDescription());
        values.put(KEY_PAYMENT_LINK, FeedFunding.getPaymentLinksAsString(feed.getPaymentLinks()));
        values.put(KEY_AUTHOR, feed.getAuthor());
        values.put(KEY_LANGUAGE, feed.getLanguage());
        values.put(KEY_IMAGE_URL, feed.getImageUrl());

        values.put(KEY_FILE_URL, feed.getLocalFileUrl());
        values.put(KEY_DOWNLOAD_URL, feed.getDownloadUrl());
        values.put(KEY_LAST_REFRESH_ATTEMPT, feed.getLastRefreshAttempt());
        values.put(KEY_LASTUPDATE, feed.getLastModified());
        values.put(KEY_TYPE, feed.getType());
        values.put(KEY_FEED_IDENTIFIER, feed.getFeedIdentifier());
        values.put(KEY_STATE, feed.getState());

        values.put(KEY_IS_PAGED, feed.isPaged());
        values.put(KEY_NEXT_PAGE_LINK, feed.getNextPageLink());
        if (feed.getItemFilter() != null && feed.getItemFilter().getValues().length > 0) {
            values.put(KEY_HIDE, TextUtils.join(",", feed.getItemFilter().getValues()));
        } else {
            values.put(KEY_HIDE, "");
        }
        values.put(KEY_SORT_ORDER, toCodeString(feed.getSortOrder()));
        values.put(KEY_LAST_UPDATE_FAILED, feed.hasLastUpdateFailed());
        if (feed.getId() == 0) {
            // Create new entry
            Log.d(this.toString(), "Inserting new Feed into db");
            feed.setId(db.insert(TABLE_NAME_FEEDS, null, values));
        } else {
            Log.d(this.toString(), "Updating existing Feed in db");
            db.update(TABLE_NAME_FEEDS, values, KEY_ID + "=?",
                    new String[]{String.valueOf(feed.getId())});
        }
        return feed.getId();
    }

    public void setFeedPreferences(FeedPreferences prefs) {
        if (prefs.getFeedID() == 0) {
            throw new IllegalArgumentException("Feed ID of preference must not be null");
        }
        ContentValues values = new ContentValues();
        values.put(KEY_AUTO_DOWNLOAD_ENABLED, prefs.getAutoDownload().code);
        values.put(KEY_KEEP_UPDATED, prefs.getKeepUpdated());
        values.put(KEY_AUTO_DELETE_ACTION, prefs.getAutoDeleteAction().code);
        values.put(KEY_FEED_VOLUME_ADAPTION, prefs.getVolumeAdaptionSetting().toInteger());
        values.put(KEY_USERNAME, prefs.getUsername());
        values.put(KEY_PASSWORD, prefs.getPassword());
        values.put(KEY_INCLUDE_FILTER, prefs.getFilter().getIncludeFilterRaw());
        values.put(KEY_EXCLUDE_FILTER, prefs.getFilter().getExcludeFilterRaw());
        values.put(KEY_MINIMAL_DURATION_FILTER, prefs.getFilter().getMinimalDurationFilter());
        values.put(KEY_FEED_PLAYBACK_SPEED, prefs.getFeedPlaybackSpeed());
        values.put(KEY_FEED_SKIP_SILENCE, prefs.getFeedSkipSilence().code);
        values.put(KEY_FEED_TAGS, prefs.getTagsAsString());
        values.put(KEY_FEED_SKIP_INTRO, prefs.getFeedSkipIntro());
        values.put(KEY_FEED_SKIP_ENDING, prefs.getFeedSkipEnding());
        values.put(KEY_EPISODE_NOTIFICATION, prefs.getShowEpisodeNotification());
        values.put(KEY_NEW_EPISODES_ACTION, prefs.getNewEpisodesAction().code);
        db.update(TABLE_NAME_FEEDS, values, KEY_ID + "=?", new String[]{String.valueOf(prefs.getFeedID())});
    }

    public void setFeedItemFilter(long feedId, Set<String> filterValues) {
        String valuesList = TextUtils.join(",", filterValues);
        Log.d(TAG, String.format(Locale.US,
                "setFeedItemFilter() called with: feedId = [%d], filterValues = [%s]", feedId, valuesList));
        ContentValues values = new ContentValues();
        values.put(KEY_HIDE, valuesList);
        db.update(TABLE_NAME_FEEDS, values, KEY_ID + "=?", new String[]{String.valueOf(feedId)});
    }

    public void setFeedItemSortOrder(long feedId, @Nullable SortOrder sortOrder) {
        ContentValues values = new ContentValues();
        values.put(KEY_SORT_ORDER, toCodeString(sortOrder));
        db.update(TABLE_NAME_FEEDS, values, KEY_ID + "=?", new String[]{String.valueOf(feedId)});
    }

    /**
     * Inserts or updates a media entry
     * Use carefully to avoid overwriting properties with stale data.
     *
     * @return the id of the entry
     */
    public long setMedia(FeedMedia media) {
        ContentValues values = new ContentValues();
        values.put(KEY_DURATION, media.getDuration());
        values.put(KEY_POSITION, media.getPosition());
        values.put(KEY_SIZE, media.getSize());
        values.put(KEY_MIME_TYPE, media.getMimeType());
        values.put(KEY_DOWNLOAD_URL, media.getDownloadUrl());
        values.put(KEY_DOWNLOAD_DATE, media.getDownloadDate());
        values.put(KEY_FILE_URL, media.getLocalFileUrl());
        values.put(KEY_HAS_EMBEDDED_PICTURE, media.hasEmbeddedPicture());
        values.put(KEY_LAST_PLAYED_TIME_STATISTICS, media.getLastPlayedTimeStatistics());

        if (media.getLastPlayedTimeHistory() != null) {
            values.put(KEY_LAST_PLAYED_TIME_HISTORY, media.getLastPlayedTimeHistory().getTime());
        } else {
            values.put(KEY_LAST_PLAYED_TIME_HISTORY, 0);
        }
        if (media.getItem() != null) {
            values.put(KEY_FEEDITEM, media.getItem().getId());
        }
        if (media.getId() == 0) {
            media.setId(db.insert(TABLE_NAME_FEED_MEDIA, null, values));
        } else {
            db.update(TABLE_NAME_FEED_MEDIA, values, KEY_ID + "=?",
                    new String[]{String.valueOf(media.getId())});
        }
        return media.getId();
    }

    /**
     * Update download state related properties of the feed media.
     */
    public void setMediaDownloadInformation(FeedMedia media) {
        if (media.getId() != 0) {
            ContentValues values = new ContentValues();
            values.put(KEY_SIZE, media.getSize());
            values.put(KEY_FILE_URL, media.getLocalFileUrl());
            values.put(KEY_DOWNLOAD_URL, media.getDownloadUrl());
            values.put(KEY_DOWNLOAD_DATE, media.getDownloadDate());
            values.put(KEY_HAS_EMBEDDED_PICTURE, media.hasEmbeddedPicture());
            db.update(TABLE_NAME_FEED_MEDIA, values, KEY_ID + "=?",
                    new String[]{String.valueOf(media.getId())});
        } else {
            Log.e(TAG, "setMediaDownloadInformation: ID of media was 0");
        }
    }

    public void setFeedMediaPlaybackInformation(FeedMedia media) {
        if (media.getId() != 0) {
            ContentValues values = new ContentValues();
            values.put(KEY_POSITION, media.getPosition());
            values.put(KEY_DURATION, media.getDuration());
            values.put(KEY_PLAYED_DURATION, media.getPlayedDuration());
            values.put(KEY_LAST_PLAYED_TIME_STATISTICS, media.getLastPlayedTimeStatistics());
            values.put(KEY_LAST_PLAYED_TIME_HISTORY, media.getLastPlayedTimeHistory().getTime());
            db.update(TABLE_NAME_FEED_MEDIA, values, KEY_ID + "=?",
                    new String[]{String.valueOf(media.getId())});
        } else {
            Log.e(TAG, "setFeedMediaPlaybackInformation: ID of media was 0");
        }
    }

    public void setFeedMediaLastPlayedTimeHistory(FeedMedia media) {
        if (media.getId() != 0) {
            ContentValues values = new ContentValues();
            values.put(KEY_LAST_PLAYED_TIME_HISTORY, media.getLastPlayedTimeHistory().getTime());
            values.put(KEY_PLAYED_DURATION, media.getPlayedDuration());
            db.update(TABLE_NAME_FEED_MEDIA, values, KEY_ID + "=?",
                    new String[]{String.valueOf(media.getId())});
        } else {
            Log.e(TAG, "setFeedMediaLastPlayedTimeHistory: ID of media was 0");
        }
    }

    public void resetAllMediaPlayedDuration() {
        try {
            db.beginTransactionNonExclusive();
            ContentValues values = new ContentValues();
            values.put(KEY_PLAYED_DURATION, 0);
            db.update(TABLE_NAME_FEED_MEDIA, values, null, new String[0]);
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Insert all FeedItems of a feed and the feed object itself in a single
     * transaction
     */
    public void setCompleteFeed(Feed... feeds) {
        try {
            db.beginTransactionNonExclusive();
            for (Feed feed : feeds) {
                setFeed(feed);
                if (feed.getItems() != null) {
                    for (FeedItem item : feed.getItems()) {
                        updateOrInsertFeedItem(item, false);
                    }
                }
                if (feed.getPreferences() != null) {
                    setFeedPreferences(feed.getPreferences());
                }
            }
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Updates the download URL of a Feed.
     */
    public void setFeedDownloadUrl(String original, String updated) {
        ContentValues values = new ContentValues();
        values.put(KEY_DOWNLOAD_URL, updated);
        db.update(TABLE_NAME_FEEDS, values, KEY_DOWNLOAD_URL + "=?", new String[]{original});
    }

    public void storeFeedItemlist(List<FeedItem> items) {
        try {
            db.beginTransactionNonExclusive();
            for (FeedItem item : items) {
                updateOrInsertFeedItem(item, true);
            }
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            db.endTransaction();
        }
    }

    public long setSingleFeedItem(FeedItem item) {
        long result = 0;
        try {
            db.beginTransactionNonExclusive();
            result = updateOrInsertFeedItem(item, true);
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            db.endTransaction();
        }
        return result;
    }

    /**
     * Inserts or updates a feeditem entry
     *
     * @param item     The FeedItem
     * @param saveFeed true if the Feed of the item should also be saved. This should be set to
     *                 false if the method is executed on a list of FeedItems of the same Feed.
     * @return the id of the entry
     */
    private long updateOrInsertFeedItem(FeedItem item, boolean saveFeed) {
        if (item.getId() == 0 && item.getPubDate() == null) {
            Log.e(TAG, "Newly saved item has no pubDate. Using current date as pubDate");
            item.setPubDate(new Date());
        }

        ContentValues values = new ContentValues();
        values.put(KEY_TITLE, item.getTitle());
        values.put(KEY_LINK, item.getLink());
        if (item.getDescription() != null) {
            values.put(KEY_DESCRIPTION, item.getDescription());
        }
        values.put(KEY_PUBDATE, item.getPubDate().getTime());
        values.put(KEY_PAYMENT_LINK, item.getPaymentLink());
        if (saveFeed && item.getFeed() != null) {
            setFeed(item.getFeed());
        }
        values.put(KEY_FEED, item.getFeed().getId());
        if (item.isNew()) {
            values.put(KEY_READ, FeedItem.NEW);
        } else if (item.isPlayed()) {
            values.put(KEY_READ, FeedItem.PLAYED);
        } else {
            values.put(KEY_READ, FeedItem.UNPLAYED);
        }
        values.put(KEY_HAS_CHAPTERS, item.getChapters() != null || item.hasChapters());
        values.put(KEY_ITEM_IDENTIFIER, item.getItemIdentifier());
        values.put(KEY_AUTO_DOWNLOAD_ENABLED, item.isAutoDownloadEnabled());
        values.put(KEY_IMAGE_URL, item.getImageUrl());
        values.put(KEY_PODCASTINDEX_CHAPTER_URL, item.getPodcastIndexChapterUrl());
        values.put(KEY_SOCIAL_INTERACT_URL, item.getSocialInteractUrl());

        // We only store one transcript url, we prefer JSON if it exists
        String type = item.getTranscriptType();
        String url = item.getTranscriptUrl();
        if (url != null) {
            values.put(KEY_PODCASTINDEX_TRANSCRIPT_TYPE, type);
            values.put(KEY_PODCASTINDEX_TRANSCRIPT_URL, url);
        }

        if (item.getId() == 0) {
            item.setId(db.insert(TABLE_NAME_FEED_ITEMS, null, values));
        } else {
            db.update(TABLE_NAME_FEED_ITEMS, values, KEY_ID + "=?",
                    new String[]{String.valueOf(item.getId())});
        }
        if (item.getMedia() != null) {
            setMedia(item.getMedia());
            item.getMedia().setItemId(item.getId());
        }
        if (item.getChapters() != null) {
            setChapters(item);
        }
        return item.getId();
    }

    /**
     * Sets the 'read' attribute of the item.
     *
     * @param played             New read status of items. See @FeedItem
     * @param resetMediaPosition Should the postition of the media item be reset?
     * @param items              List of items to upgrade
     */
    public void setFeedItemsRead(int played, boolean resetMediaPosition, List<FeedItem> items) {
        try {
            db.beginTransactionNonExclusive();
            ContentValues values = new ContentValues();
            for (FeedItem item : items) {
                values.clear();
                values.put(KEY_READ, played);
                db.update(TABLE_NAME_FEED_ITEMS, values, KEY_ID + "=?", new String[]{String.valueOf(item.getId())});

                if (resetMediaPosition && item.hasMedia()) {
                    values.clear();
                    values.put(KEY_POSITION, 0);
                    db.update(TABLE_NAME_FEED_MEDIA, values, KEY_ID + "=?",
                            new String[]{String.valueOf(item.getMedia().getId())});
                }
            }
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            db.endTransaction();
        }
    }

    private void setChapters(FeedItem item) {
        ContentValues values = new ContentValues();
        for (Chapter chapter : item.getChapters()) {
            values.put(KEY_TITLE, chapter.getTitle());
            values.put(KEY_START, chapter.getStart());
            values.put(KEY_FEEDITEM, item.getId());
            values.put(KEY_LINK, chapter.getLink());
            values.put(KEY_IMAGE_URL, chapter.getImageUrl());
            if (chapter.getId() == 0) {
                chapter.setId(db.insert(TABLE_NAME_SIMPLECHAPTERS, null, values));
            } else {
                db.update(TABLE_NAME_SIMPLECHAPTERS, values, KEY_ID + "=?",
                        new String[]{String.valueOf(chapter.getId())});
            }
        }
    }

    public void resetPagedFeedPage(Feed feed) {
        final String sql = "UPDATE " + TABLE_NAME_FEEDS
                + " SET " + KEY_NEXT_PAGE_LINK + "=" + KEY_DOWNLOAD_URL
                + " WHERE " + KEY_ID + "=" + feed.getId();
        db.execSQL(sql);
    }

    public void setFeedLastUpdateFailed(long feedId, boolean failed) {
        final String sql = "UPDATE " + TABLE_NAME_FEEDS
                + " SET " + KEY_LAST_UPDATE_FAILED + "=" + (failed ? "1" : "0")
                + "," + KEY_LAST_REFRESH_ATTEMPT + "=" + System.currentTimeMillis()
                + " WHERE " + KEY_ID + "=" + feedId;
        db.execSQL(sql);
    }

    public void setFeedCustomTitle(long feedId, String customTitle) {
        ContentValues values = new ContentValues();
        values.put(KEY_CUSTOM_TITLE, customTitle);
        db.update(TABLE_NAME_FEEDS, values, KEY_ID + "=?", new String[]{String.valueOf(feedId)});
    }

    public void setFeedState(long feedId, int state) {
        ContentValues values = new ContentValues();
        values.put(KEY_STATE, state);
        db.update(TABLE_NAME_FEEDS, values, KEY_ID + "=?", new String[]{String.valueOf(feedId)});
    }

    /**
     * Inserts or updates a download status.
     */
    public long setDownloadStatus(DownloadResult status) {
        ContentValues values = new ContentValues();
        values.put(KEY_FEEDFILE, status.getFeedfileId());
        values.put(KEY_FEEDFILETYPE, status.getFeedfileType());
        values.put(KEY_REASON, status.getReason().getCode());
        values.put(KEY_SUCCESSFUL, status.isSuccessful());
        values.put(KEY_COMPLETION_DATE, status.getCompletionDate().getTime());
        values.put(KEY_REASON_DETAILED, status.getReasonDetailed());
        values.put(KEY_DOWNLOADSTATUS_TITLE, status.getTitle());
        if (status.getId() == 0) {
            status.setId(db.insert(TABLE_NAME_DOWNLOAD_LOG, null, values));
        } else {
            db.update(TABLE_NAME_DOWNLOAD_LOG, values, KEY_ID + "=?",
                    new String[]{String.valueOf(status.getId())});
        }
        return status.getId();
    }

    public void setFavorites(List<FeedItem> favorites) {
        ContentValues values = new ContentValues();
        try {
            db.beginTransactionNonExclusive();
            db.delete(TABLE_NAME_FAVORITES, null, null);
            for (int i = 0; i < favorites.size(); i++) {
                FeedItem item = favorites.get(i);
                values.put(KEY_ID, i);
                values.put(KEY_FEEDITEM, item.getId());
                values.put(KEY_FEED, item.getFeed().getId());
                db.insertWithOnConflict(TABLE_NAME_FAVORITES, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Adds the item to favorites
     */
    public void addFavoriteItems(List<FeedItem> items) {
        if (items.isEmpty()) {
            return;
        }
        db.execSQL("INSERT INTO " + TABLE_NAME_FAVORITES + " (" + KEY_FEEDITEM + ", " + KEY_FEED + ")"
                + " SELECT " + KEY_ID + ", " + KEY_FEED
                + " FROM " + TABLE_NAME_FEED_ITEMS
                + " WHERE " + KEY_ID + " IN (" + getItemIds(items) + ")"
                + " AND " + KEY_ID + " NOT IN (SELECT " + KEY_FEEDITEM + " FROM " + TABLE_NAME_FAVORITES + ")");
    }

    public void removeFavoriteItems(List<FeedItem> items) {
        if (items.isEmpty()) {
            return;
        }
        db.execSQL("DELETE FROM " + TABLE_NAME_FAVORITES
                + " WHERE " + KEY_FEEDITEM + " IN (" + getItemIds(items) + ")");
    }

    public void setQueue(List<FeedItem> queue) {
        ContentValues values = new ContentValues();
        try {
            db.beginTransactionNonExclusive();
            db.delete(TABLE_NAME_QUEUE, null, null);
            for (int i = 0; i < queue.size(); i++) {
                FeedItem item = queue.get(i);
                values.put(KEY_ID, i);
                values.put(KEY_FEEDITEM, item.getId());
                values.put(KEY_FEED, item.getFeed().getId());
                db.insertWithOnConflict(TABLE_NAME_QUEUE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            db.endTransaction();
        }
    }

    public void clearQueue() {
        db.delete(TABLE_NAME_QUEUE, null, null);
    }

    /**
     * Remove the listed items and their FeedMedia entries.
     */
    public void removeFeedItems(@NonNull List<FeedItem> items) {
        try {
            StringBuilder mediaIds = new StringBuilder();
            StringBuilder itemIds = new StringBuilder();
            for (FeedItem item : items) {
                if (item.getMedia() != null) {
                    if (mediaIds.length() != 0) {
                        mediaIds.append(",");
                    }
                    mediaIds.append(item.getMedia().getId());
                }
                if (itemIds.length() != 0) {
                    itemIds.append(",");
                }
                itemIds.append(item.getId());
            }

            db.beginTransactionNonExclusive();
            db.delete(TABLE_NAME_SIMPLECHAPTERS, KEY_FEEDITEM + " IN (" + itemIds + ")", null);
            db.delete(TABLE_NAME_DOWNLOAD_LOG, KEY_FEEDFILETYPE + "=" + FeedMedia.FEEDFILETYPE_FEEDMEDIA
                            + " AND " + KEY_FEEDFILE + " IN (" + mediaIds + ")", null);
            db.delete(TABLE_NAME_FEED_MEDIA, KEY_ID + " IN (" + mediaIds + ")", null);
            db.delete(TABLE_NAME_FEED_ITEMS, KEY_ID + " IN (" + itemIds + ")", null);
            db.delete(TABLE_NAME_FAVORITES, KEY_FEEDITEM + " IN (" + itemIds + ")", null);
            // Smart playlist membership is not covered by a foreign key, and SQLite does not
            // enforce ON DELETE CASCADE unless PRAGMA foreign_keys is enabled, which AntennaPod
            // does not set. Without this the rows outlive their episodes and inflate the counts
            // reported by getSmartPlaylistEpisodeCount.
            db.delete(TABLE_NAME_SMART_PLAYLIST_EPISODES,
                    KEY_SMART_PLAYLIST_EPISODE_ID + " IN (" + itemIds + ")", null);
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Remove a feed with all its FeedItems and Media entries.
     */
    public void removeFeed(Feed feed) {
        try {
            db.beginTransactionNonExclusive();
            if (feed.getItems() != null) {
                removeFeedItems(feed.getItems());
            }
            // delete download log entries for feed
            db.delete(TABLE_NAME_DOWNLOAD_LOG, KEY_FEEDFILE + "=? AND " + KEY_FEEDFILETYPE + "=?",
                    new String[]{String.valueOf(feed.getId()), String.valueOf(Feed.FEEDFILETYPE_FEED)});

            db.delete(TABLE_NAME_FEEDS, KEY_ID + "=?",
                    new String[]{String.valueOf(feed.getId())});
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            db.endTransaction();
        }
    }

    public void clearPlaybackHistory() {
        ContentValues values = new ContentValues();
        values.put(KEY_LAST_PLAYED_TIME_HISTORY, 0);
        db.update(TABLE_NAME_FEED_MEDIA, values, null, null);
    }

    public void clearDownloadLog() {
        db.delete(TABLE_NAME_DOWNLOAD_LOG, null, null);
    }

    public void clearOldDownloadLog() {
        db.execSQL("DELETE FROM " + PodDBAdapter.TABLE_NAME_DOWNLOAD_LOG + " WHERE "
                + PodDBAdapter.KEY_COMPLETION_DATE + "<" + (System.currentTimeMillis() - 7L * 24L * 3600L * 1000L));
    }

    /**
     * Get all Feeds from the Feed Table.
     *
     * @return The cursor of the query
     */
    public final Cursor getAllFeedsCursor() {
        final String query = "SELECT " + KEYS_FEED
                + " FROM " + TABLE_NAME_FEEDS
                + " ORDER BY " + TABLE_NAME_FEEDS + "." + KEY_TITLE + " COLLATE NOCASE ASC";
        return db.rawQuery(query, null);
    }

    public final Cursor getFeedCursorDownloadUrls(boolean subscribedOnly) {
        String selection = subscribedOnly ? KEY_STATE + "=" + Feed.STATE_SUBSCRIBED : null;
        return db.query(TABLE_NAME_FEEDS, new String[]{KEY_ID, KEY_DOWNLOAD_URL}, selection, null, null, null, null);
    }

    /**
     * Returns a cursor with all FeedItems of a Feed. Uses FEEDITEM_SEL_FI_SMALL
     *
     * @param feed The feed you want to get the FeedItems from.
     * @return The cursor of the query
     */
    public final Cursor getItemsOfFeedCursor(final Feed feed, FeedItemFilter filter, SortOrder sortOrder,
                                             int offset, int limit) {
        String orderByQuery = FeedItemSortQuery.generateFrom(sortOrder);
        filter = new FeedItemFilter(filter, FeedItemFilter.INCLUDE_ALL_FEED_STATES);
        String filterQuery = FeedItemFilterQuery.generateFrom(filter);
        String whereClauseAnd = "".equals(filterQuery) ? "" : " AND " + filterQuery;
        final String query = SELECT_FEED_ITEMS_AND_MEDIA
                + " WHERE " + TABLE_NAME_FEED_ITEMS + "." + KEY_FEED + "=" + feed.getId()
                + whereClauseAnd
                + " ORDER BY " + orderByQuery
                + " LIMIT " + offset + ", " + limit;
        return db.rawQuery(query, null);
    }

    /**
     * Return the description and content_encoded of item
     */
    public final Cursor getDescriptionOfItem(final FeedItem item) {
        final String query = "SELECT " + KEY_DESCRIPTION
                + " FROM " + TABLE_NAME_FEED_ITEMS
                + " WHERE " + KEY_ID + "=" + item.getId();
        return db.rawQuery(query, null);
    }

    public final Cursor getSimpleChaptersOfFeedItemCursor(final FeedItem item) {
        return db.query(TABLE_NAME_SIMPLECHAPTERS, null, KEY_FEEDITEM
                        + "=?", new String[]{String.valueOf(item.getId())}, null,
                null, null
        );
    }

    public final Cursor getDownloadLog(final int feedFileType, final long feedFileId, final long limit) {
        final String query = "SELECT * FROM " + TABLE_NAME_DOWNLOAD_LOG +
                " WHERE " + KEY_FEEDFILE + "=" + feedFileId + " AND " + KEY_FEEDFILETYPE + "=" + feedFileType
                + " ORDER BY " + KEY_COMPLETION_DATE + " DESC LIMIT " + limit;
        return db.rawQuery(query, null);
    }

    public final Cursor getDownloadLogCursor(final int limit) {
        return db.query(TABLE_NAME_DOWNLOAD_LOG, null, null, null, null,
                null, KEY_COMPLETION_DATE + " DESC LIMIT " + limit);
    }

    /**
     * Returns a cursor which contains all feed items in the queue. The returned
     * cursor uses the FEEDITEM_SEL_FI_SMALL selection.
     * cursor uses the FEEDITEM_SEL_FI_SMALL selection.
     */
    public final Cursor getQueueCursor() {
        final String query = "SELECT " + KEYS_FEED_ITEM_WITHOUT_DESCRIPTION + ", " + KEYS_FEED_MEDIA
                + " FROM " + TABLE_NAME_QUEUE
                + " INNER JOIN " + TABLE_NAME_FEED_ITEMS
                + " ON " + SELECT_KEY_ITEM_ID + " = " + TABLE_NAME_QUEUE + "." + KEY_FEEDITEM
                +  JOIN_FEED_ITEM_AND_MEDIA
                + " ORDER BY " + TABLE_NAME_QUEUE + "." + KEY_ID;
        return db.rawQuery(query, null);
    }

    public Cursor getQueueIDCursor() {
        return db.query(TABLE_NAME_QUEUE, new String[]{KEY_FEEDITEM}, null, null, null, null, KEY_ID + " ASC", null);
    }

    public Cursor getNextInQueue(final FeedItem item) {
        final String query = "SELECT " + KEYS_FEED_ITEM_WITHOUT_DESCRIPTION + ", " + KEYS_FEED_MEDIA
                + " FROM " + TABLE_NAME_QUEUE
                + " INNER JOIN " + TABLE_NAME_FEED_ITEMS
                + " ON " + SELECT_KEY_ITEM_ID + " = " + TABLE_NAME_QUEUE + "." + KEY_FEEDITEM
                +  JOIN_FEED_ITEM_AND_MEDIA
                + " WHERE Queue.ID > (SELECT Queue.ID FROM Queue WHERE Queue.FeedItem = "
                +  item.getId()
                + ")"
                + " ORDER BY Queue.ID"
                + " LIMIT 1";
        return db.rawQuery(query, null);
    }

    public final Cursor getPausedQueueCursor(int limit) {
        final String hasPositionOrRecentlyPlayed = TABLE_NAME_FEED_MEDIA + "."  + KEY_POSITION + " >= 1000"
                + " OR " + TABLE_NAME_FEED_MEDIA + "." + KEY_LAST_PLAYED_TIME_STATISTICS
                + " >= " + (System.currentTimeMillis() - 30000);
        final String query = "SELECT " + KEYS_FEED_ITEM_WITHOUT_DESCRIPTION + ", " + KEYS_FEED_MEDIA
                + " FROM " + TABLE_NAME_QUEUE
                + " INNER JOIN " + TABLE_NAME_FEED_ITEMS
                + " ON " + SELECT_KEY_ITEM_ID + " = " + TABLE_NAME_QUEUE + "." + KEY_FEEDITEM
                +  JOIN_FEED_ITEM_AND_MEDIA
                + " ORDER BY (CASE WHEN " + hasPositionOrRecentlyPlayed + " THEN "
                    + TABLE_NAME_FEED_MEDIA + "." + KEY_LAST_PLAYED_TIME_STATISTICS + " ELSE 0 END) DESC , "
                + TABLE_NAME_QUEUE + "." + KEY_ID
                + " LIMIT " + limit;
        return db.rawQuery(query, null);
    }

    public void setFeedItems(int oldState, int newState) {
        setFeedItems(oldState, newState, 0);
    }

    public void setFeedItems(int oldState, int newState, long feedId) {
        String sql = "UPDATE " + TABLE_NAME_FEED_ITEMS + " SET " + KEY_READ + "=" + newState;
        if (feedId > 0) {
            sql += " WHERE " + KEY_FEED + "=" + feedId;
        }
        if (FeedItem.NEW <= oldState && oldState <= FeedItem.PLAYED) {
            sql += feedId > 0 ? " AND " : " WHERE ";
            sql += KEY_READ + "=" + oldState;
        }
        db.execSQL(sql);
    }

    public final Cursor getEpisodesCursor(int offset, int limit, FeedItemFilter filter, SortOrder sortOrder) {
        String orderByQuery = FeedItemSortQuery.generateFrom(sortOrder);
        String filterQuery = FeedItemFilterQuery.generateFrom(filter);
        String whereClause = "".equals(filterQuery) ? "" : " WHERE " + filterQuery;
        final String query = SELECT_FEED_ITEMS_AND_MEDIA + whereClause
                + "ORDER BY " +  orderByQuery + " LIMIT " + offset + ", " + limit;
        return db.rawQuery(query, null);
    }

    public final Cursor getEpisodeCountCursor(FeedItemFilter filter) {
        String filterQuery = FeedItemFilterQuery.generateFrom(filter);
        String whereClause = "".equals(filterQuery) ? "" : " WHERE " + filterQuery;
        final String query = "SELECT count(" + TABLE_NAME_FEED_ITEMS + "." + KEY_ID + ") FROM " + TABLE_NAME_FEED_ITEMS
                + JOIN_FEED_ITEM_AND_MEDIA + whereClause;
        return db.rawQuery(query, null);
    }

    public final Cursor getFeedEpisodeCountCursor(long feedId, FeedItemFilter filter) {
        filter = new FeedItemFilter(filter, FeedItemFilter.INCLUDE_ALL_FEED_STATES);
        String filterQuery = FeedItemFilterQuery.generateFrom(filter);
        String whereAndClause = "".equals(filterQuery) ? "" : " AND " + filterQuery;
        final String query = "SELECT count(" + TABLE_NAME_FEED_ITEMS + "." + KEY_ID + ") FROM " + TABLE_NAME_FEED_ITEMS
                + JOIN_FEED_ITEM_AND_MEDIA
                + " WHERE " + TABLE_NAME_FEED_ITEMS + "." + KEY_FEED + "=" + feedId + whereAndClause;
        return db.rawQuery(query, null);
    }

    public Cursor getRandomEpisodesCursor(int limit, int seed) {
        long oneHourAgo = System.currentTimeMillis() - 1000L * 3600L;
        final String allItems = SELECT_FEED_ITEMS_AND_MEDIA
                + " WHERE (" + KEY_READ + " = " + FeedItem.NEW + " OR " + KEY_READ + " = " + FeedItem.UNPLAYED + ") "
                    // Only from the last two years. Older episodes often contain broken covers and stuff like that
                    + " AND " + KEY_PUBDATE + " > " + (System.currentTimeMillis() - 1000L * 3600L * 24L * 356L * 2)
                    // Hide episodes that have been played but not completed
                    + " AND (" + KEY_LAST_PLAYED_TIME_STATISTICS + " == 0"
                        + " OR " + KEY_LAST_PLAYED_TIME_STATISTICS + " > " + oneHourAgo + ")"
                    + " AND " + SELECT_WHERE_FEED_IS_SUBSCRIBED;
        final String query = "SELECT MAX(" + randomEpisodeNumber(seed) + "), * FROM (" + allItems + ")"
                + " GROUP BY " + KEY_FEED
                + " ORDER BY " + randomEpisodeNumber(seed * 3) + " DESC LIMIT " + limit;
        return db.rawQuery(query, null);
    }

    /**
     * SQLite does not support random seeds. Create our own "random" number based on that seed and the item ID
     */
    private String randomEpisodeNumber(int seed) {
        return "((" + SELECT_KEY_ITEM_ID + " * " + seed + ") % 46471)";
    }

    public final Cursor getFeedItemFromMediaIdCursor(long mediaId) {
        final String query = SELECT_FEED_ITEMS_AND_MEDIA
                + " WHERE " + SELECT_KEY_MEDIA_ID + " = " + mediaId;
        return db.rawQuery(query, null);
    }

    public final Cursor getFeedCursor(final long id) {
        final String query = "SELECT " + KEYS_FEED
                + " FROM " + TABLE_NAME_FEEDS
                + " WHERE " + SELECT_KEY_FEED_ID + " = " + id;
        return db.rawQuery(query, null);
    }

    public final Cursor getFeedItemCursor(final String id) {
        return getFeedItemCursor(new String[]{id});
    }

    public final Cursor getFeedItemCursor(final String[] ids) {
        if (ids.length > IN_OPERATOR_MAXIMUM) {
            throw new IllegalArgumentException("number of IDs must not be larger than " + IN_OPERATOR_MAXIMUM);
        }
        final String query = SELECT_FEED_ITEMS_AND_MEDIA
                + " WHERE " + SELECT_KEY_ITEM_ID + " IN (" + TextUtils.join(",", ids) + ")";
        return db.rawQuery(query, null);
    }

    public final Cursor getFeedItemCursorByUrl(List<String> urls) {
        if (urls.size() > IN_OPERATOR_MAXIMUM) {
            throw new IllegalArgumentException("number of IDs must not be larger than " + IN_OPERATOR_MAXIMUM);
        }
        StringBuilder urlsString = new StringBuilder();
        for (int i = 0; i < urls.size(); i++) {
            if (i != 0) {
                urlsString.append(",");
            }
            urlsString.append(DatabaseUtils.sqlEscapeString(urls.get(i)));
        }
        final String query = SELECT_FEED_ITEMS_AND_MEDIA
                + " WHERE " + KEY_DOWNLOAD_URL + " IN (" + urlsString + ")"
                + " ORDER BY " + KEY_LAST_PLAYED_TIME_HISTORY + " DESC";
        return db.rawQuery(query, null);
    }

    public final Cursor getFeedItemCursor(final String guid, final String episodeUrl) {
        String escapedEpisodeUrl = DatabaseUtils.sqlEscapeString(episodeUrl);
        String whereClauseCondition = TABLE_NAME_FEED_MEDIA + "." + KEY_DOWNLOAD_URL + "=" + escapedEpisodeUrl;

        if (guid != null) {
            String escapedGuid = DatabaseUtils.sqlEscapeString(guid);
            whereClauseCondition = TABLE_NAME_FEED_ITEMS + "." + KEY_ITEM_IDENTIFIER + "=" + escapedGuid;
        }

        final String query = SELECT_FEED_ITEMS_AND_MEDIA
                + " INNER JOIN " + TABLE_NAME_FEEDS
                + " ON " + TABLE_NAME_FEED_ITEMS + "." + KEY_FEED + "=" + TABLE_NAME_FEEDS + "." + KEY_ID
                + " WHERE " + whereClauseCondition;
        return db.rawQuery(query, null);
    }

    public final Cursor getMonthlyStatisticsCursor() {
        final String query = "SELECT SUM(" + KEY_PLAYED_DURATION + ") AS total_duration"
                + ", strftime('%m', datetime(" + KEY_LAST_PLAYED_TIME_STATISTICS + "/1000, 'unixepoch')) AS month"
                + ", strftime('%Y', datetime(" + KEY_LAST_PLAYED_TIME_STATISTICS + "/1000, 'unixepoch')) AS year"
                + " FROM " + TABLE_NAME_FEED_MEDIA
                + " WHERE " + KEY_LAST_PLAYED_TIME_STATISTICS + " > 0 AND " + KEY_PLAYED_DURATION + " > 0"
                + " GROUP BY year, month"
                + " ORDER BY year, month";
        return db.rawQuery(query, null);
    }

    public final Cursor getFeedStatisticsCursor(boolean includeMarkedAsPlayed, long timeFilterFrom,
                                                long timeFilterTo, long sixMonthsAgo) {
        final String lastPlayedTimeStatistics = TABLE_NAME_FEED_MEDIA + "." + KEY_LAST_PLAYED_TIME_STATISTICS;
        String wasStarted = TABLE_NAME_FEED_MEDIA + "." + KEY_LAST_PLAYED_TIME_HISTORY + " > 0"
                + " AND " + TABLE_NAME_FEED_MEDIA + "." + KEY_PLAYED_DURATION + " > 0";
        if (includeMarkedAsPlayed) {
            wasStarted = "(" + wasStarted + ") OR "
                    + TABLE_NAME_FEED_ITEMS + "." + KEY_READ + "=" + FeedItem.PLAYED + " OR "
                    + TABLE_NAME_FEED_MEDIA + "." + KEY_POSITION + "> 0";
        }
        final String timeFilter = lastPlayedTimeStatistics + ">=" + timeFilterFrom
                + " AND " + lastPlayedTimeStatistics + "<" + timeFilterTo;
        String playedTime = TABLE_NAME_FEED_MEDIA + "." + KEY_PLAYED_DURATION;
        if (includeMarkedAsPlayed) {
            playedTime = "(CASE WHEN " + playedTime + " != 0"
                    + " THEN " + playedTime + " ELSE ("
                            + "CASE WHEN " + TABLE_NAME_FEED_ITEMS + "." + KEY_READ + "=" + FeedItem.PLAYED
                                + " THEN " + TABLE_NAME_FEED_MEDIA + "." + KEY_DURATION + " ELSE 0 END"
                    + ") END)";
        }

        final String query = "SELECT " + KEYS_FEED + ", "
                        + "COUNT(*) AS num_episodes, "
                        + "MIN(CASE WHEN " + lastPlayedTimeStatistics + " > 0"
                                + " THEN " + lastPlayedTimeStatistics
                                + " ELSE " + Long.MAX_VALUE + " END) AS oldest_date, "
                        + "SUM(CASE WHEN (" + wasStarted + ") THEN 1 ELSE 0 END) AS episodes_started, "
                        + "IFNULL(SUM(CASE WHEN (" + timeFilter + ")"
                                + " THEN (" + playedTime + ") ELSE 0 END), 0) AS played_time, "
                        + "IFNULL(SUM(" + TABLE_NAME_FEED_MEDIA + "." + KEY_DURATION + "), 0) AS total_time, "
                        + "SUM(CASE WHEN " + TABLE_NAME_FEED_MEDIA + "." + KEY_DOWNLOAD_DATE + " > 0"
                                + " OR " + TABLE_NAME_FEEDS + "." + KEY_DOWNLOAD_URL
                                + " LIKE '" + Feed.PREFIX_LOCAL_FOLDER + "%'"
                                + " THEN 1 ELSE 0 END) AS num_downloaded, "
                        + "SUM(CASE WHEN " + TABLE_NAME_FEED_MEDIA + "." + KEY_DOWNLOAD_DATE + " > 0"
                                + " OR " + TABLE_NAME_FEEDS + "." + KEY_DOWNLOAD_URL
                                + " LIKE '" + Feed.PREFIX_LOCAL_FOLDER + "%'"
                                + " THEN " + TABLE_NAME_FEED_MEDIA + "." + KEY_SIZE
                                + " ELSE 0 END) AS download_size, "
                        + "SUM(CASE WHEN " + TABLE_NAME_FEED_ITEMS + "." + KEY_READ + " != " + FeedItem.PLAYED
                                + " AND " + TABLE_NAME_FEED_ITEMS + "." + KEY_PUBDATE + " >= " + sixMonthsAgo
                                + " THEN 1 ELSE 0 END) AS num_recent_unplayed "
                + " FROM " + TABLE_NAME_FEED_ITEMS
                + JOIN_FEED_ITEM_AND_MEDIA
                + " INNER JOIN " + TABLE_NAME_FEEDS
                + " ON " + TABLE_NAME_FEED_ITEMS + "." + KEY_FEED + "=" + TABLE_NAME_FEEDS + "." + KEY_ID
                + " WHERE " + TABLE_NAME_FEEDS + "." + KEY_STATE + "!=" + Feed.STATE_NOT_SUBSCRIBED
                + " GROUP BY " + TABLE_NAME_FEEDS + "." + KEY_ID;
        return db.rawQuery(query, null);
    }

    public final Cursor getTimeBetweenReleaseAndPlayback(long timeFilterFrom, long timeFilterTo) {
        final String from = " FROM " + TABLE_NAME_FEED_ITEMS
                + JOIN_FEED_ITEM_AND_MEDIA
                + " WHERE " + TABLE_NAME_FEED_MEDIA + "." + KEY_LAST_PLAYED_TIME_STATISTICS + ">=" + timeFilterFrom
                        + " AND " + TABLE_NAME_FEED_ITEMS + "." + KEY_PUBDATE + ">=" + timeFilterFrom
                        + " AND " + TABLE_NAME_FEED_MEDIA + "." + KEY_LAST_PLAYED_TIME_STATISTICS + "<" + timeFilterTo;
        final String query = "SELECT " + TABLE_NAME_FEED_MEDIA + "." + KEY_LAST_PLAYED_TIME_STATISTICS
                + " - " + TABLE_NAME_FEED_ITEMS + "." + KEY_PUBDATE + " AS diff"
                + from
                + " ORDER BY diff ASC"
                + " LIMIT 1"
                + " OFFSET (SELECT count(*)/2 " + from + ")";
        return db.rawQuery(query, null);
    }

    public int getQueueSize() {
        final String query = String.format("SELECT COUNT(%s) FROM %s", KEY_ID, TABLE_NAME_QUEUE);
        try (Cursor c = db.rawQuery(query, null)) {
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
            return 0;
        }
    }

    public final Map<Long, Integer> getFeedCounters(FeedCounter setting, long... feedIds) {
        String whereRead;
        String localFeedCondition = KEY_FEED + " IN (SELECT " + KEY_ID + " FROM " + TABLE_NAME_FEEDS
                + " WHERE " + KEY_DOWNLOAD_URL + " LIKE '" + Feed.PREFIX_LOCAL_FOLDER + "%')";
        switch (setting) {
            case SHOW_NEW:
                whereRead = KEY_READ + "=" + FeedItem.NEW;
                break;
            case SHOW_UNPLAYED:
                whereRead = "(" + KEY_READ + "=" + FeedItem.NEW
                        + " OR " + KEY_READ + "=" + FeedItem.UNPLAYED + ")";
                break;
            case SHOW_DOWNLOADED:
                whereRead = "(" + KEY_DOWNLOAD_DATE + ">0 OR " + localFeedCondition + ")";
                break;
            case SHOW_DOWNLOADED_UNPLAYED:
                whereRead = "(" + KEY_READ + "=" + FeedItem.NEW
                        + " OR " + KEY_READ + "=" + FeedItem.UNPLAYED + ")"
                        + " AND (" + KEY_DOWNLOAD_DATE + ">0 OR " + localFeedCondition + ")";
                break;
            case SHOW_NONE:
                // deliberate fall-through
            default: // NONE
                return new HashMap<>();
        }
        return conditionalFeedCounterRead(whereRead, feedIds);
    }

    private Map<Long, Integer> conditionalFeedCounterRead(String whereRead, long... feedIds) {
        String limitFeeds;
        if (feedIds.length > 0) {
            // work around TextUtils.join wanting only boxed items
            // and StringUtils.join() causing NoSuchMethodErrors on MIUI
            StringBuilder builder = new StringBuilder();
            for (long id : feedIds) {
                builder.append(id);
                builder.append(',');
            }
            // there's an extra ',', get rid of it
            builder.deleteCharAt(builder.length() - 1);
            limitFeeds = KEY_FEED + " IN (" + builder.toString() + ") AND ";
        } else {
            limitFeeds = SELECT_WHERE_FEED_IS_SUBSCRIBED + " AND ";
        }

        final String query = "SELECT " + KEY_FEED + ", COUNT(" + TABLE_NAME_FEED_ITEMS + "." + KEY_ID + ") AS count "
                + " FROM " + TABLE_NAME_FEED_ITEMS
                + " LEFT JOIN " + TABLE_NAME_FEED_MEDIA + " ON "
                + TABLE_NAME_FEED_ITEMS + "." + KEY_ID + "=" + TABLE_NAME_FEED_MEDIA + "." + KEY_FEEDITEM
                + " WHERE " + limitFeeds + " "
                + whereRead + " GROUP BY " + KEY_FEED;

        Map<Long, Integer> result = new HashMap<>();
        try (Cursor c = db.rawQuery(query, null)) {
            if (!c.moveToFirst()) {
                return result;
            }
            do {
                long feedId = c.getLong(0);
                int count = c.getInt(1);
                result.put(feedId, count);
            } while (c.moveToNext());
        }
        return result;
    }

    public final Map<Long, Integer> getPlayedEpisodesCounters(long... feedIds) {
        String whereRead = KEY_READ + "=" + FeedItem.PLAYED;
        return conditionalFeedCounterRead(whereRead, feedIds);
    }

    public final Map<Long, Long> getMostRecentItemDates() {
        final String query = "SELECT " + KEY_FEED + ","
                + " MAX(" + TABLE_NAME_FEED_ITEMS + "." + KEY_PUBDATE + ") AS most_recent_pubdate"
                + " FROM " + TABLE_NAME_FEED_ITEMS
                + " GROUP BY " + KEY_FEED;

        Map<Long, Long> result = new HashMap<>();
        try (Cursor c = db.rawQuery(query, null)) {
            if (!c.moveToFirst()) {
                return result;
            }
            do {
                long feedId = c.getLong(0);
                long date = c.getLong(1);
                result.put(feedId, date);
            } while (c.moveToNext());
        }
        return result;
    }

    /**
     * Uses DatabaseUtils to escape a search query and removes ' at the
     * beginning and the end of the string returned by the escape method.
     */
    private String[] prepareSearchQuery(String query) {
        String[] queryWords = query.split("\\s+");
        for (int i = 0; i < queryWords.length; ++i) {
            StringBuilder builder = new StringBuilder();
            DatabaseUtils.appendEscapedSQLString(builder, queryWords[i]);
            builder.deleteCharAt(0);
            builder.deleteCharAt(builder.length() - 1);
            queryWords[i] = builder.toString();
        }

        return queryWords;
    }

    /**
     * Searches for the given query in various values of all items or the items
     * of a specified feed.
     *
     * @return A cursor with all search results in SEL_FI_EXTRA selection.
     */
    public Cursor searchItems(long feedID, String searchQuery, FeedItemFilter filter) {
        final String[] queryWords = prepareSearchQuery(searchQuery);

        String queryFeedId;
        if (feedID != 0) {
            // search items in specific feed
            queryFeedId = KEY_FEED + " = " + feedID;
        } else {
            // search through all items
            queryFeedId = "1 = 1";
        }

        String queryStart = SELECT_FEED_ITEMS_AND_MEDIA_WITH_DESCRIPTION + " WHERE " + queryFeedId;
        FeedItemFilter effectiveFilter = feedID != 0
                ? new FeedItemFilter(filter, FeedItemFilter.INCLUDE_ALL_FEED_STATES)
                : filter;
        String filterQuery = FeedItemFilterQuery.generateFrom(effectiveFilter);
        if (!filterQuery.isEmpty()) {
            queryStart += " AND " + filterQuery;
        }
        queryStart += " AND (";
        StringBuilder sb = new StringBuilder(queryStart);

        for (int i = 0; i < queryWords.length; i++) {
            sb
                    .append("(")
                    .append(KEY_DESCRIPTION + " LIKE '%").append(queryWords[i])
                    .append("%' OR ")
                    .append(KEY_TITLE).append(" LIKE '%").append(queryWords[i])
                    .append("%') ");

            if (i != queryWords.length - 1) {
                sb.append("AND ");
            }
        }

        sb.append(") ORDER BY " + KEY_PUBDATE + " DESC LIMIT 300");

        return db.rawQuery(sb.toString(), null);
    }

    /**
     * Searches for the given query in various values of all feeds.
     *
     * @return A cursor with all search results in SEL_FI_EXTRA selection.
     */
    public Cursor searchFeeds(String searchQuery, FeedItemFilter filter) {
        final String[] queryWords = prepareSearchQuery(searchQuery);
        List<String> allowedStates = new ArrayList<>();
        if (filter.includeSubscribed) {
            allowedStates.add(String.valueOf(Feed.STATE_SUBSCRIBED));
        }
        if (filter.includeArchived) {
            allowedStates.add(String.valueOf(Feed.STATE_ARCHIVED));
        }
        if (filter.includeNotSubscribed) {
            allowedStates.add(String.valueOf(Feed.STATE_NOT_SUBSCRIBED));
        }
        if (allowedStates.isEmpty()) {
            allowedStates.add(String.valueOf(Feed.STATE_SUBSCRIBED));
        }
        String queryStart = "SELECT " + KEYS_FEED + " FROM " + TABLE_NAME_FEEDS
                + " WHERE " + KEY_STATE + " IN (" + TextUtils.join(",", allowedStates) + ")";
        StringBuilder sb = new StringBuilder(queryStart);

        for (int i = 0; i < queryWords.length; i++) {
            sb
                    .append(" AND (")
                    .append(KEY_TITLE).append(" LIKE '%").append(queryWords[i])
                    .append("%' OR ")
                    .append(KEY_CUSTOM_TITLE).append(" LIKE '%").append(queryWords[i])
                    .append("%' OR ")
                    .append(KEY_AUTHOR).append(" LIKE '%").append(queryWords[i])
                    .append("%' OR ")
                    .append(KEY_DESCRIPTION).append(" LIKE '%").append(queryWords[i])
                    .append("%') ");
        }

        sb.append(" ORDER BY " + KEY_TITLE + " ASC LIMIT 300");

        return db.rawQuery(sb.toString(), null);
    }

    private String getItemIds(List<FeedItem> items) {
        StringBuilder itemIds = new StringBuilder();
        for (FeedItem item : items) {
            if (itemIds.length() != 0) {
                itemIds.append(",");
            }
            itemIds.append(item.getId());
        }
        return itemIds.toString();
    }

    /**
     * Insert raw data to the database.
     * Call method only for unit tests.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public void insertTestData(@NonNull String table, @NonNull ContentValues values) {
        db.insert(table, null, values);
    }

    // FORK: Smart Playlist CRUD methods

    public Cursor getAllSmartPlaylistsCursor() {
        final String query = "SELECT * FROM " + TABLE_NAME_SMART_PLAYLISTS
                + " ORDER BY " + KEY_SMART_PLAYLIST_CREATED_AT + " ASC";
        return db.rawQuery(query, null);
    }

    public Cursor getSmartPlaylistCursor(long playlistId) {
        final String query = "SELECT * FROM " + TABLE_NAME_SMART_PLAYLISTS
                + " WHERE " + KEY_ID + " = ?";
        return db.rawQuery(query, new String[]{String.valueOf(playlistId)});
    }

    public Cursor getSmartPlaylistRulesCursor(long playlistId) {
        final String query = "SELECT * FROM " + TABLE_NAME_SMART_PLAYLIST_RULES
                + " WHERE " + KEY_SMART_PLAYLIST_ID + " = ?"
                + " ORDER BY " + KEY_SMART_PLAYLIST_POSITION + " ASC";
        return db.rawQuery(query, new String[]{String.valueOf(playlistId)});
    }

    public int getSmartPlaylistEpisodeCount(long playlistId) {
        final String query = "SELECT COUNT(*) FROM " + TABLE_NAME_SMART_PLAYLIST_EPISODES
                + " WHERE " + KEY_SMART_PLAYLIST_ID + " = ?";
        try (Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(playlistId)})) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        }
        return 0;
    }

    public Cursor getNextInSmartQueueCursor(long queueId, long currentItemId) {
        final String query = "SELECT " + KEYS_FEED_ITEM_WITHOUT_DESCRIPTION + ", " + KEYS_FEED_MEDIA
                + " FROM " + TABLE_NAME_SMART_PLAYLIST_EPISODES
                + " INNER JOIN " + TABLE_NAME_FEED_ITEMS
                + " ON " + TABLE_NAME_FEED_ITEMS + "." + KEY_ID + " = "
                + TABLE_NAME_SMART_PLAYLIST_EPISODES + "." + KEY_SMART_PLAYLIST_EPISODE_ID
                + JOIN_FEED_ITEM_AND_MEDIA
                + " WHERE " + TABLE_NAME_SMART_PLAYLIST_EPISODES + "." + KEY_SMART_PLAYLIST_ID
                + " = ?"
                + " AND " + TABLE_NAME_SMART_PLAYLIST_EPISODES + "." + KEY_SMART_PLAYLIST_POSITION
                + " > (SELECT " + KEY_SMART_PLAYLIST_POSITION + " FROM " + TABLE_NAME_SMART_PLAYLIST_EPISODES
                + " WHERE " + KEY_SMART_PLAYLIST_ID + " = ?"
                + " AND " + KEY_SMART_PLAYLIST_EPISODE_ID + " = ?)"
                + " ORDER BY " + TABLE_NAME_SMART_PLAYLIST_EPISODES + "." + KEY_SMART_PLAYLIST_POSITION + " ASC"
                + " LIMIT 1";
        String queueIdStr = String.valueOf(queueId);
        return db.rawQuery(query, new String[]{queueIdStr, queueIdStr, String.valueOf(currentItemId)});
    }

    public Cursor getSmartPlaylistEpisodesCursor(long playlistId) {
        final String query = "SELECT " + KEYS_FEED_ITEM_WITHOUT_DESCRIPTION + ", " + KEYS_FEED_MEDIA
                + " FROM " + TABLE_NAME_SMART_PLAYLIST_EPISODES
                + " INNER JOIN " + TABLE_NAME_FEED_ITEMS
                + " ON " + TABLE_NAME_FEED_ITEMS + "." + KEY_ID + " = "
                + TABLE_NAME_SMART_PLAYLIST_EPISODES + "." + KEY_SMART_PLAYLIST_EPISODE_ID
                + JOIN_FEED_ITEM_AND_MEDIA
                + " WHERE " + TABLE_NAME_SMART_PLAYLIST_EPISODES + "." + KEY_SMART_PLAYLIST_ID
                + " = ?"
                + " ORDER BY " + TABLE_NAME_SMART_PLAYLIST_EPISODES + "." + KEY_SMART_PLAYLIST_POSITION + " ASC";
        return db.rawQuery(query, new String[]{String.valueOf(playlistId)});
    }

    /**
     * Counts episodes in a smart playlist that have not been played, ignoring their position in
     * the queue. Episodes skipped past stay unplayed and sit behind the queue's cursor, so a
     * position-aware count would only be meaningful for whichever queue is currently active.
     */
    public int getSmartPlaylistUnplayedCount(long playlistId) {
        final String query = "SELECT COUNT(*)"
                + " FROM " + TABLE_NAME_SMART_PLAYLIST_EPISODES
                + " INNER JOIN " + TABLE_NAME_FEED_ITEMS
                + " ON " + TABLE_NAME_FEED_ITEMS + "." + KEY_ID + " = "
                + TABLE_NAME_SMART_PLAYLIST_EPISODES + "." + KEY_SMART_PLAYLIST_EPISODE_ID
                + " WHERE " + TABLE_NAME_SMART_PLAYLIST_EPISODES + "." + KEY_SMART_PLAYLIST_ID + " = ?"
                + " AND " + TABLE_NAME_FEED_ITEMS + "." + KEY_READ + " != " + FeedItem.PLAYED;
        try (Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(playlistId)})) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
            return 0;
        }
    }

    public Cursor getSmartPlaylistRuleMatchesCursor(SmartPlaylistRule rule) {
        String whereClause = SmartPlaylistRuleQuery.generateWhereClause(rule);
        String orderClause = SmartPlaylistRuleQuery.generateOrderClause(rule);

        String query = "SELECT " + KEYS_FEED_ITEM_WITHOUT_DESCRIPTION + ", " + KEYS_FEED_MEDIA
                + " FROM " + TABLE_NAME_FEED_ITEMS
                + JOIN_FEED_ITEM_AND_MEDIA
                + " LEFT JOIN " + TABLE_NAME_FEEDS
                + " ON " + TABLE_NAME_FEED_ITEMS + "." + KEY_FEED + " = "
                + TABLE_NAME_FEEDS + "." + KEY_ID;

        if (!android.text.TextUtils.isEmpty(whereClause)) {
            query += " WHERE " + whereClause;
        }
        query += " ORDER BY " + orderClause;

        if (rule.getEpisodeLimit() > 0) {
            query += " LIMIT " + rule.getEpisodeLimit();
        }

        return db.rawQuery(query, null);
    }

    public long setSmartPlaylist(SmartPlaylist playlist) {
        ContentValues values = new ContentValues();
        values.put(KEY_SMART_PLAYLIST_NAME, playlist.getName());
        values.put(KEY_SMART_PLAYLIST_AUTO_REGENERATE, playlist.isAutoRegenerate() ? 1 : 0);
        values.put(KEY_SMART_PLAYLIST_GENERATED_AT, playlist.getGeneratedAt());
        values.put(KEY_SMART_PLAYLIST_UPDATED_AT, System.currentTimeMillis());

        if (playlist.getId() == 0) {
            values.put(KEY_SMART_PLAYLIST_CREATED_AT, System.currentTimeMillis());
            playlist.setId(db.insert(TABLE_NAME_SMART_PLAYLISTS, null, values));
        } else {
            db.update(TABLE_NAME_SMART_PLAYLISTS, values, KEY_ID + "=?",
                    new String[]{String.valueOf(playlist.getId())});
        }
        return playlist.getId();
    }

    public long setSmartPlaylistRule(SmartPlaylistRule rule) {
        ContentValues values = new ContentValues();
        values.put(KEY_SMART_PLAYLIST_ID, rule.getPlaylistId());
        values.put(KEY_SMART_PLAYLIST_POSITION, rule.getPosition());
        values.put(KEY_SMART_PLAYLIST_FILTER_PROPERTIES, rule.getFilterProperties());
        values.put(KEY_SMART_PLAYLIST_FEED_IDS, rule.getFeedIds());
        values.put(KEY_SMART_PLAYLIST_FEED_TAGS, rule.getFeedTags());
        values.put(KEY_SMART_PLAYLIST_MAX_AGE_DAYS, rule.getMaxAgeDays());
        values.put(KEY_SMART_PLAYLIST_MIN_DURATION_MS, rule.getMinDurationMs());
        values.put(KEY_SMART_PLAYLIST_MAX_DURATION_MS, rule.getMaxDurationMs());
        values.put(KEY_SMART_PLAYLIST_MEDIA_TYPE, rule.getMediaType());
        values.put(KEY_SMART_PLAYLIST_EPISODE_LIMIT, rule.getEpisodeLimit());
        values.put(KEY_SMART_PLAYLIST_SORT_ORDER, rule.getSortOrder());

        if (rule.getId() == 0) {
            rule.setId(db.insert(TABLE_NAME_SMART_PLAYLIST_RULES, null, values));
        } else {
            db.update(TABLE_NAME_SMART_PLAYLIST_RULES, values, KEY_ID + "=?",
                    new String[]{String.valueOf(rule.getId())});
        }
        return rule.getId();
    }

    public void deleteSmartPlaylistRulesForPlaylist(long playlistId) {
        db.delete(TABLE_NAME_SMART_PLAYLIST_RULES, KEY_SMART_PLAYLIST_ID + "=?",
                new String[]{String.valueOf(playlistId)});
    }

    public void deleteSmartPlaylistEpisodes(long playlistId) {
        db.delete(TABLE_NAME_SMART_PLAYLIST_EPISODES, KEY_SMART_PLAYLIST_ID + "=?",
                new String[]{String.valueOf(playlistId)});
    }

    public void insertSmartPlaylistEpisode(long playlistId, long episodeId, int position) {
        ContentValues values = new ContentValues();
        values.put(KEY_SMART_PLAYLIST_ID, playlistId);
        values.put(KEY_SMART_PLAYLIST_EPISODE_ID, episodeId);
        values.put(KEY_SMART_PLAYLIST_POSITION, position);
        db.insert(TABLE_NAME_SMART_PLAYLIST_EPISODES, null, values);
    }

    /**
     * Replaces the cached episode list of a smart playlist atomically. Regeneration runs on the
     * playback thread while the UI reads the same rows, so doing the delete and the inserts in
     * one transaction keeps readers from observing an empty or half-rebuilt playlist, and stops
     * a crash mid-rebuild from leaving a truncated one behind. It also collapses what used to be
     * one round trip per episode into a single commit.
     */
    public void replaceSmartPlaylistEpisodes(long playlistId, Iterable<Long> episodeIds) {
        try {
            db.beginTransactionNonExclusive();
            deleteSmartPlaylistEpisodes(playlistId);
            int position = 0;
            for (Long episodeId : episodeIds) {
                insertSmartPlaylistEpisode(playlistId, episodeId, position++);
            }
            db.setTransactionSuccessful();
        } catch (SQLException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } finally {
            db.endTransaction();
        }
    }

    public void deleteSmartPlaylist(long playlistId) {
        // SQLite does not honor ON DELETE CASCADE unless PRAGMA foreign_keys is enabled, which
        // AntennaPod does not set, so remove the child rows explicitly to avoid orphaned data.
        deleteSmartPlaylistRulesForPlaylist(playlistId);
        deleteSmartPlaylistEpisodes(playlistId);
        db.delete(TABLE_NAME_SMART_PLAYLISTS, KEY_ID + "=?",
                new String[]{String.valueOf(playlistId)});
    }

    /**
     * Called when a database corruption happens.
     */
    public static class PodDbErrorHandler implements DatabaseErrorHandler {
        @Override
        public void onCorruption(SQLiteDatabase db) {
            Log.e(TAG, "Database corrupted: " + db.getPath());

            File dbPath = new File(db.getPath());
            File backupFolder = PodDBAdapter.context.getExternalFilesDir(null);
            File backupFile = new File(backupFolder, "CorruptedDatabaseBackup.db");
            try {
                FileUtils.copyFile(dbPath, backupFile);
                Log.d(TAG, "Dumped database to " + backupFile.getPath());
            } catch (IOException e) {
                Log.d(TAG, Log.getStackTraceString(e));
            }

            new DefaultDatabaseErrorHandler().onCorruption(db); // This deletes the database
        }
    }

    /**
     * Helper class for opening the Antennapod database.
     */
    /**
     * FORK: creates everything this fork adds — its bookkeeping table plus the Smart Queue
     * tables and indexes — if they are not already present.
     *
     * <p>Every statement is {@code IF NOT EXISTS}, so this is safe on a fresh database and on
     * one that already has them. Applying the fork's schema this way, rather than gating it
     * behind {@code if (oldVersion < 3120000)}, means it cannot be missed because of whatever
     * version stamp the database happens to carry — which matters precisely because upstream
     * will eventually move its own numbering through the range this fork already claimed.
     */
    static void createForkSchema(final SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_FORK_SCHEMA);
        db.execSQL(CREATE_TABLE_SMART_PLAYLISTS);
        db.execSQL(CREATE_TABLE_SMART_PLAYLIST_RULES);
        db.execSQL(CREATE_TABLE_SMART_PLAYLIST_EPISODES);
        db.execSQL(CREATE_INDEX_SMART_PLAYLIST_EPISODES_PLAYLIST);
        db.execSQL(CREATE_INDEX_SMART_PLAYLIST_RULES_PLAYLIST);
    }

    /**
     * FORK: converts Smart Queue tables written by the earlier fork branch.
     *
     * <p>That branch named these columns without the {@code sp_} prefix this one uses, so its
     * databases — including any backup restored from one — carry {@code name}, {@code
     * playlist_id} and so on. Every query here asks for the prefixed names, so the playlists
     * would be unreadable and the feature silently empty.
     *
     * <p>Recreate-and-copy rather than {@code ALTER TABLE ... RENAME COLUMN}, which needs
     * SQLite 3.25 and {@code minSdk} is 23. Dropping the renamed original also frees its index
     * names, so {@link #createForkSchema} can recreate them afterwards.
     *
     * <p>Detected per table by looking for a legacy column, so this is a no-op on databases
     * already using the current names and safe to run on every upgrade.
     */
    static void migrateLegacySmartQueueSchema(final SQLiteDatabase db) {
        convertLegacyTable(db, TABLE_NAME_SMART_PLAYLISTS, "name", CREATE_TABLE_SMART_PLAYLISTS,
                new String[]{KEY_ID, "name", "auto_regenerate", "generated_at",
                        "created_at", "updated_at"},
                new String[]{KEY_ID, KEY_SMART_PLAYLIST_NAME, KEY_SMART_PLAYLIST_AUTO_REGENERATE,
                        KEY_SMART_PLAYLIST_GENERATED_AT, KEY_SMART_PLAYLIST_CREATED_AT,
                        KEY_SMART_PLAYLIST_UPDATED_AT});

        convertLegacyTable(db, TABLE_NAME_SMART_PLAYLIST_RULES, "playlist_id",
                CREATE_TABLE_SMART_PLAYLIST_RULES,
                new String[]{KEY_ID, "playlist_id", "position", "filter_properties", "feed_ids",
                        "feed_tags", "max_age_days", "min_duration_ms", "max_duration_ms",
                        "media_type", "episode_limit", "sort_order"},
                new String[]{KEY_ID, KEY_SMART_PLAYLIST_ID, KEY_SMART_PLAYLIST_POSITION,
                        KEY_SMART_PLAYLIST_FILTER_PROPERTIES, KEY_SMART_PLAYLIST_FEED_IDS,
                        KEY_SMART_PLAYLIST_FEED_TAGS, KEY_SMART_PLAYLIST_MAX_AGE_DAYS,
                        KEY_SMART_PLAYLIST_MIN_DURATION_MS, KEY_SMART_PLAYLIST_MAX_DURATION_MS,
                        KEY_SMART_PLAYLIST_MEDIA_TYPE, KEY_SMART_PLAYLIST_EPISODE_LIMIT,
                        KEY_SMART_PLAYLIST_SORT_ORDER});

        convertLegacyTable(db, TABLE_NAME_SMART_PLAYLIST_EPISODES, "playlist_id",
                CREATE_TABLE_SMART_PLAYLIST_EPISODES,
                new String[]{KEY_ID, "playlist_id", "episode_id", "position"},
                new String[]{KEY_ID, KEY_SMART_PLAYLIST_ID, KEY_SMART_PLAYLIST_EPISODE_ID,
                        KEY_SMART_PLAYLIST_POSITION});
    }

    private static void convertLegacyTable(final SQLiteDatabase db, final String table,
                                           final String legacyMarkerColumn, final String createSql,
                                           final String[] legacyColumns,
                                           final String[] currentColumns) {
        if (!hasColumn(db, table, legacyMarkerColumn)) {
            return;
        }
        String legacyTable = table + "_legacy";
        db.execSQL("DROP TABLE IF EXISTS " + legacyTable);
        db.execSQL("ALTER TABLE " + table + " RENAME TO " + legacyTable);
        db.execSQL(createSql);
        db.execSQL("INSERT INTO " + table + " (" + TextUtils.join(",", currentColumns) + ") SELECT "
                + TextUtils.join(",", legacyColumns) + " FROM " + legacyTable);
        db.execSQL("DROP TABLE " + legacyTable);
        Log.i(TAG, "Converted legacy Smart Queue table " + table);
    }

    private static boolean hasColumn(final SQLiteDatabase db, final String table,
                                     final String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameColumn = cursor.getColumnIndex("name");
            if (nameColumn < 0) {
                return false;
            }
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(nameColumn))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * FORK: how far through upstream's migration chain this database has actually been taken.
     *
     * <p>Falls back to reading it off the version stamp the first time, for databases written
     * before this bookkeeping existed. A stamp at or above {@link #LEGACY_FORK_STAMP} can only
     * have been produced by the fork's original migration, which applied upstream's
     * {@link #LEGACY_FORK_UPSTREAM_LEVEL} schema and nothing beyond it. Any lower stamp is a
     * genuine upstream version and is trusted as-is.
     */
    static int readUpstreamSchemaLevel(final SQLiteDatabase db, final int stampedVersion) {
        try (Cursor cursor = db.rawQuery("SELECT " + KEY_FORK_SCHEMA_VALUE + " FROM "
                        + TABLE_NAME_FORK_SCHEMA + " WHERE " + KEY_FORK_SCHEMA_NAME + "=?",
                new String[]{FORK_SCHEMA_UPSTREAM_LEVEL})) {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        }
        return stampedVersion >= LEGACY_FORK_STAMP ? LEGACY_FORK_UPSTREAM_LEVEL : stampedVersion;
    }

    /** FORK: records the upstream chain position so it is not replayed on the next upgrade. */
    static void writeUpstreamSchemaLevel(final SQLiteDatabase db, final int level) {
        ContentValues values = new ContentValues();
        values.put(KEY_FORK_SCHEMA_NAME, FORK_SCHEMA_UPSTREAM_LEVEL);
        values.put(KEY_FORK_SCHEMA_VALUE, level);
        db.insertWithOnConflict(TABLE_NAME_FORK_SCHEMA, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static class PodDBHelper extends SQLiteOpenHelper {
        /**
         * Constructor.
         *
         * @param context Context to use
         * @param name    Name of the database
         * @param factory to use for creating cursor objects
         */
        public PodDBHelper(final Context context, final String name, final CursorFactory factory) {
            super(context, name, factory, VERSION, new PodDbErrorHandler());
        }

        @Override
        public void onCreate(final SQLiteDatabase db) {
            db.execSQL(CREATE_TABLE_FEEDS);
            db.execSQL(CREATE_TABLE_FEED_ITEMS);
            db.execSQL(CREATE_TABLE_FEED_MEDIA);
            db.execSQL(CREATE_TABLE_DOWNLOAD_LOG);
            db.execSQL(CREATE_TABLE_QUEUE);
            db.execSQL(CREATE_TABLE_SIMPLECHAPTERS);
            db.execSQL(CREATE_TABLE_FAVORITES);

            db.execSQL(CREATE_INDEX_FEEDITEMS_FEED);
            db.execSQL(CREATE_INDEX_FEEDITEMS_PUBDATE);
            db.execSQL(CREATE_INDEX_FEEDITEMS_READ);
            db.execSQL(CREATE_INDEX_FEEDMEDIA_FEEDITEM);
            db.execSQL(CREATE_INDEX_QUEUE_FEEDITEM);
            db.execSQL(CREATE_INDEX_SIMPLECHAPTERS_FEEDITEM);
            // FORK: Smart Queue tables plus fork bookkeeping. A database created here already
            // has upstream's current schema, so record that level rather than leaving it to be
            // inferred later.
            createForkSchema(db);
            writeUpstreamSchemaLevel(db, UPSTREAM_SCHEMA_LEVEL);
        }

        @Override
        public void onUpgrade(final SQLiteDatabase db, final int oldVersion, final int newVersion) {
            Log.w("DBAdapter", "Upgrading from version " + oldVersion + " to " + newVersion + ".");
            DBUpgrader.upgrade(db, oldVersion, newVersion);
        }
    }
}
