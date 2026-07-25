package de.danoeh.antennapod.storage.database.mapper;

import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.SmartPlaylistRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Smart playlist rules are user-authored and are re-read from the database before being compiled
 * into SQL, so every string field is untrusted input reaching a hand-built query. The rule
 * compiler is currently safe; these tests exist so that a later refactor cannot quietly undo
 * that. Each test pins one specific mechanism the safety depends on.
 */
@RunWith(RobolectricTestRunner.class)
public class SmartPlaylistRuleQueryTest {

    /**
     * A rule with no conditions. The default constructor pre-sets
     * {@code filterProperties = "unplayed,downloaded"}, which would otherwise add clauses to
     * every assertion here and hide the field actually under test.
     */
    private static SmartPlaylistRule rule() {
        SmartPlaylistRule rule = new SmartPlaylistRule();
        rule.setFilterProperties("");
        return rule;
    }

    // --- ORDER BY: allowlist, never the user's string -----------------------------------------

    @Test
    public void sortOrderIsAllowlistedNotInterpolated() {
        // ORDER BY cannot be parameterized, so the only defense is that the user's value is
        // compared and never emitted.
        SmartPlaylistRule rule = rule();
        rule.setSortOrder("NEWEST, (SELECT 1) --");
        String order = SmartPlaylistRuleQuery.generateOrderClause(rule);

        assertFalse("user text must not reach ORDER BY", order.contains("SELECT"));
        assertFalse(order.contains("--"));
        // Falls back to the documented default rather than failing open.
        assertTrue(order.endsWith("DESC"));
    }

    @Test
    public void everyKnownSortOrderMapsToASafeConstant() {
        // Note RANDOM legitimately maps to "RANDOM()", so this checks the shape of the emitted
        // clause rather than the absence of the keyword.
        for (String value : new String[]{"NEWEST", "OLDEST", "SHORTEST", "LONGEST", "RANDOM"}) {
            SmartPlaylistRule rule = rule();
            rule.setSortOrder(value);
            String order = SmartPlaylistRuleQuery.generateOrderClause(rule);

            assertFalse(value + " produced an empty ORDER BY", order.isEmpty());
            assertFalse(order.contains("'"));
            assertFalse(order.contains(";"));
            assertFalse(order.contains("--"));
        }
    }

    @Test
    public void nullSortOrderDoesNotCrash() {
        SmartPlaylistRule rule = rule();
        rule.setSortOrder(null);
        assertTrue(SmartPlaylistRuleQuery.generateOrderClause(rule).endsWith("DESC"));
    }

    // --- Feed ids: numeric validation, fail closed ---------------------------------------------

    @Test
    public void feedIdsRejectNonNumericValues() {
        SmartPlaylistRule rule = rule();
        rule.setFeedIds("1,2 OR 1=1,3");
        String where = SmartPlaylistRuleQuery.generateWhereClause(rule);

        assertFalse("non-numeric id must be dropped", where.contains("OR 1=1"));
        assertTrue(where.contains("1"));
        assertTrue(where.contains("3"));
    }

    @Test
    public void feedIdsFailClosedWhenNothingValidates() {
        // Dropping the condition entirely would widen the rule from "these podcasts" to
        // "every podcast" -- the opposite of what the user asked for.
        SmartPlaylistRule rule = rule();
        rule.setFeedIds("garbage,'; DROP TABLE Feeds; --");
        String where = SmartPlaylistRuleQuery.generateWhereClause(rule);

        assertTrue("must fail closed", where.contains("1=0"));
        assertFalse(where.contains("DROP TABLE"));
    }

    // --- Tags: escaping and whole-entry matching -----------------------------------------------

    @Test
    public void tagQuotesAreEscaped() {
        SmartPlaylistRule rule = rule();
        rule.setFeedTags("O'Brien");
        String where = SmartPlaylistRuleQuery.generateWhereClause(rule);

        // sqlEscapeString doubles the quote; an unescaped one would terminate the literal.
        assertTrue(where.contains("O''Brien"));
    }

    @Test
    public void tagWildcardsAreTreatedLiterally() {
        SmartPlaylistRule rule = rule();
        rule.setFeedTags("100%_done");
        String where = SmartPlaylistRuleQuery.generateWhereClause(rule);

        assertTrue("LIKE wildcards must be escaped", where.contains("\\%"));
        assertTrue(where.contains("\\_"));
        assertTrue("escaping only works if ESCAPE is declared", where.contains("ESCAPE"));
    }

    @Test
    public void tagInjectionAttemptStaysInsideStringLiteral() {
        SmartPlaylistRule rule = rule();
        rule.setFeedTags("x' OR '1'='1");
        String where = SmartPlaylistRuleQuery.generateWhereClause(rule);

        // The payload survives as data (with doubled quotes) but never as syntax.
        assertFalse(where.contains("' OR '1'='1"));
        assertTrue(where.contains("''"));
    }

    @Test
    public void tagMatchesWholeEntryNotSubstring() {
        // Feeds.tags is a TAG_SEPARATOR-joined list. Matching '%News%' would also hit a feed
        // tagged "NewsRoom", silently pulling in the wrong podcasts.
        SmartPlaylistRule rule = rule();
        rule.setFeedTags("News");
        String where = SmartPlaylistRuleQuery.generateWhereClause(rule);

        assertTrue("pattern must be delimited by the tag separator",
                where.contains(FeedPreferences.TAG_SEPARATOR + "News" + FeedPreferences.TAG_SEPARATOR));
    }

    @Test
    public void tagsFailClosedWhenAllBlank() {
        SmartPlaylistRule rule = rule();
        rule.setFeedTags(" , , ");
        assertTrue(SmartPlaylistRuleQuery.generateWhereClause(rule).contains("1=0"));
    }

    // --- Media type ----------------------------------------------------------------------------

    @Test
    public void mediaTypeIsEscaped() {
        SmartPlaylistRule rule = rule();
        rule.setMediaType("audio' OR '1'='1");
        String where = SmartPlaylistRuleQuery.generateWhereClause(rule);

        assertFalse(where.contains("' OR '1'='1"));
        assertTrue(where.contains("ESCAPE"));
    }

    // --- Numeric fields are type-constrained ---------------------------------------------------

    @Test
    public void numericFieldsEmitOnlyDigits() {
        SmartPlaylistRule rule = rule();
        rule.setMinDurationMs(60000);
        rule.setMaxDurationMs(600000);
        rule.setMaxAgeDays(7);
        String where = SmartPlaylistRuleQuery.generateWhereClause(rule);

        assertTrue(where.contains("60000"));
        assertTrue(where.contains("600000"));
        // Non-positive values are omitted rather than emitted as a broken comparison.
        SmartPlaylistRule empty = rule();
        empty.setMinDurationMs(0);
        empty.setMaxAgeDays(0);
        assertEquals("", SmartPlaylistRuleQuery.generateWhereClause(empty));
    }

    // --- Filter properties never reach SQL as text ---------------------------------------------

    @Test
    public void unknownFilterPropertiesAreDiscarded() {
        // FeedItemFilter maps known tokens to booleans; anything else sets no flag, so the raw
        // string cannot appear in the query.
        SmartPlaylistRule rule = rule();
        rule.setFilterProperties("unplayed,'; DELETE FROM FeedItems; --");
        String where = SmartPlaylistRuleQuery.generateWhereClause(rule);

        assertFalse(where.contains("DELETE FROM"));
        assertFalse(where.contains("--"));
    }

    @Test
    public void emptyRuleProducesNoConditions() {
        assertEquals("", SmartPlaylistRuleQuery.generateWhereClause(rule()));
    }
}
